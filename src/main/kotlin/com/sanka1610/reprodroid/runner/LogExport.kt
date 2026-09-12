package com.sanka1610.reprodroid.runner

import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream

internal data class LogExportResult(
    val bytes: Long,
    val range: String,
    val missing: Boolean,
    val truncated: Boolean,
)

internal class LogExportException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Local-only, bounded text exports.  There is deliberately no HTTP route:
 * paired clients continue to use the existing authenticated Job log API.
 */
internal object RunnerLogExporter {
    const val MAX_EXPORT_BYTES = 32L * 1024 * 1024
    private const val MAX_HEADER_BYTES = 4L * 1024
    private const val BODY_LIMIT = MAX_EXPORT_BYTES - MAX_HEADER_BYTES
    private const val PAGE_SIZE = 500
    private const val MAX_OPERATIONAL_SOURCES = 8
    private val rotatedName = Regex("runner\\.log\\.[0-9]{4}-[0-9]{2}-[0-9]{2}\\.[0-9]+\\.gz")

    fun exportRunnerLog(
        stateDirectory: Path,
        output: Path,
        clock: Clock = Clock.systemUTC(),
    ): LogExportResult {
        val root = stateDirectory.toAbsolutePath().normalize()
        val outputPath = validateOutput(output)
        validateDirectoryChain(root)
        val files = operationalLogFiles(root)
        val missing = files.none { it.fileName.toString() == RunnerLogging.OPERATIONAL_LOG_FILE }
        var truncated = false
        var range = "unknown"
        var reason: String? = if (missing) {
            "The active Runner operational log file is unavailable; retained rotations may be incomplete."
        } else {
            null
        }
        val spool = BodySpool(outputPath.parent)
        try {
            if (files.isNotEmpty()) {
                range = "file=${files.first().fileName}..${files.last().fileName}"
                files.forEach { source ->
                    if (spool.isFull) {
                        truncated = true
                        return@forEach
                    }
                    val copied = copyLogFile(source, spool)
                    truncated = truncated || copied
                }
            } else {
                reason = "No retained Runner operational log file is available."
            }
            val header = header(
                kind = "runner",
                generatedAt = Instant.now(clock).toString(),
                range = range,
                missing = missing,
                truncated = truncated,
                reason = reason,
            )
            spool.close()
            return publish(outputPath, header, spool, range, missing, truncated)
        } catch (failure: LogExportException) {
            throw failure
        } catch (failure: Throwable) {
            throw LogExportException("Runner log export could not be completed safely.", failure)
        } finally {
            spool.closeAndDelete()
        }
    }

    fun exportJobLog(
        store: SQLiteJobStore,
        stateDirectory: Path,
        jobId: String,
        output: Path,
        clock: Clock = Clock.systemUTC(),
    ): LogExportResult {
        requireCanonicalJobId(jobId)
        requireLocalJob(store, jobId)
        val outputPath = validateOutput(output)
        val root = stateDirectory.toAbsolutePath().normalize()
        validateDirectoryChain(root)
        val logsDirectory = root.resolve("logs")
        validateDirectoryChain(logsDirectory)
        val logPath = logsDirectory.resolve("$jobId.log")
        if (Files.isSymbolicLink(logPath)) {
            throw LogExportException("The selected job log is not a safe regular file.")
        }
        val missing = !Files.isRegularFile(logPath, NOFOLLOW_LINKS)
        var truncated = false
        var firstSequence: Long? = null
        var lastSequence: Long? = null
        var reason: String? = null
        val spool = BodySpool(outputPath.parent)
        try {
            if (missing) {
                reason = "The selected job log file is unavailable; no log bytes were exported."
            } else {
                var afterSequence = 0L
                while (true) {
                    // Ownership may be changed by the explicit adoption flow while a
                    // large export is paging through SQLite.  Recheck before every
                    // page so a revoked/local-to-paired transition fails closed.
                    requireLocalJob(store, jobId)
                    val page = try {
                        store.getLogs(jobId, afterSequence, PAGE_SIZE)
                            ?: throw LogExportException("The selected job is no longer available.")
                    } catch (failure: LogExportException) {
                        throw failure
                    } catch (failure: Throwable) {
                        throw LogExportException("The selected job log could not be read safely.", failure)
                    }
                    for (entry in page.entries) {
                        val line = formatJobEntry(entry)
                        if (!spool.tryWrite(line.toByteArray(Charsets.UTF_8))) {
                            truncated = true
                            break
                        }
                        firstSequence = firstSequence ?: entry.sequence
                        lastSequence = entry.sequence
                    }
                    if (truncated || !page.hasMore || page.entries.isEmpty()) break
                    check(page.nextAfterSequence > afterSequence) { "JOB_LOG_CURSOR_STALLED" }
                    afterSequence = page.nextAfterSequence
                }
            }
            val range = if (firstSequence == null) "sequence=none" else "sequence=$firstSequence..$lastSequence"
            val header = header(
                kind = "job:$jobId",
                generatedAt = Instant.now(clock).toString(),
                range = range,
                missing = missing,
                truncated = truncated,
                reason = reason,
            )
            spool.close()
            return publish(
                outputPath,
                header,
                spool,
                range,
                missing,
                truncated,
                beforePublish = { requireLocalJob(store, jobId) },
            )
        } catch (failure: LogExportException) {
            throw failure
        } catch (failure: Throwable) {
            throw LogExportException("Job log export could not be completed safely.", failure)
        } finally {
            spool.closeAndDelete()
        }
    }

    private fun operationalLogFiles(root: Path): List<Path> {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw LogExportException("Runner state is not a safe directory.")
        val files = try {
            Files.list(root).use { paths ->
                paths.filter { path ->
                    path.fileName.toString() == RunnerLogging.OPERATIONAL_LOG_FILE ||
                        rotatedName.matches(path.fileName.toString())
                }.toList().sortedWith(compareBy<Path> { it.fileName.toString() == RunnerLogging.OPERATIONAL_LOG_FILE }
                    .thenBy { it.fileName.toString() })
            }
        } catch (failure: Throwable) {
            throw LogExportException("Runner operational logs could not be enumerated safely.", failure)
        }
        if (files.size > MAX_OPERATIONAL_SOURCES) {
            throw LogExportException("Runner operational logs exceed the bounded retention source count.")
        }
        files.forEach { path ->
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                throw LogExportException("Runner operational logs contain an unsafe entry.")
            }
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                if (Files.getOwner(path, NOFOLLOW_LINKS) != Files.getOwner(root, NOFOLLOW_LINKS)) {
                    throw LogExportException("Runner operational logs contain an entry owned by another user.")
                }
                val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                if (permissions.any {
                        it == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE ||
                            it == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
                    }
                ) {
                    throw LogExportException("Runner operational logs contain a writable shared entry.")
                }
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
        return files
    }

    private fun copyLogFile(source: Path, spool: BodySpool): Boolean {
        val input = try {
            val raw = Files.newInputStream(source, READ, NOFOLLOW_LINKS)
            if (source.fileName.toString().endsWith(".gz")) GZIPInputStream(BufferedInputStream(raw)) else BufferedInputStream(raw)
        } catch (failure: Throwable) {
            throw LogExportException("Runner operational logs could not be opened safely.", failure)
        }
        input.use { stream ->
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(stream, decoder).use { reader ->
                val buffer = CharArray(8 * 1024)
                while (!spool.isFull) {
                    val count = reader.read(buffer)
                    if (count < 0) return false
                    val encoded = String(buffer, 0, count).toByteArray(Charsets.UTF_8)
                    if (!spool.tryWrite(encoded)) return true
                }
                // Probe one character so an exact boundary is not incorrectly reported as truncated.
                return reader.read() >= 0
            }
        }
    }

    private fun formatJobEntry(entry: LogEntry): String {
        val message = entry.message.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
        return "${entry.sequence}\t${entry.timestamp}\t${entry.level.name}\t$message\n"
    }

    private fun header(
        kind: String,
        generatedAt: String,
        range: String,
        missing: Boolean,
        truncated: Boolean,
        reason: String?,
    ): ByteArray {
        val lines = buildList {
            add("# ReproDroid Runner log export")
            add("# format: runner-log-export@1")
            add("# target: $kind")
            add("# generatedAt: ${safeHeader(generatedAt)}")
            add("# range: ${safeHeader(range)}")
            add("# missing: $missing")
            add("# truncated: $truncated")
            reason?.let { add("# reason: ${safeHeader(it)}") }
            add("# WARNING: build output is not anonymized and may contain secrets or local paths; inspect before sharing.")
            add("# The export contains retained data only; bounded retention or an unavailable source may omit earlier records.")
            add("# --- begin log ---")
        }
        val bytes = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        check(bytes.size.toLong() <= MAX_HEADER_BYTES) { "LOG_EXPORT_HEADER_TOO_LARGE" }
        return bytes
    }

    private fun publish(
        output: Path,
        header: ByteArray,
        spool: BodySpool,
        range: String,
        missing: Boolean,
        truncated: Boolean,
        beforePublish: () -> Unit = {},
    ): LogExportResult {
        val temporary = createPrivateTemporary(output.parent)
        var moved = false
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                writeFully(channel, header)
                FileChannel.open(spool.path, READ, NOFOLLOW_LINKS).use { body ->
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        val count = body.read(buffer)
                        if (count < 0) break
                        check(count > 0) { "LOG_EXPORT_BODY_COPY_FAILED" }
                        buffer.flip()
                        writeFully(channel, buffer)
                        buffer.clear()
                    }
                }
                channel.force(true)
            }
            val bytes = Files.size(temporary)
            check(bytes <= MAX_EXPORT_BYTES) { "LOG_EXPORT_SIZE_LIMIT" }
            beforePublish()
            if (Files.exists(output, NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                throw LogExportException("The export output already exists or is a symbolic link.")
            }
            Files.move(temporary, output, ATOMIC_MOVE)
            moved = true
            return LogExportResult(bytes, range, missing, truncated)
        } catch (failure: LogExportException) {
            throw failure
        } catch (failure: Throwable) {
            throw LogExportException("The export output could not be published atomically.", failure)
        } finally {
            if (!moved) runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun validateOutput(output: Path): Path {
        val target = output.toAbsolutePath().normalize()
        val parent = target.parent ?: throw LogExportException("The export output must name a file in an existing directory.")
        validateDirectoryChain(parent)
        if (Files.exists(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw LogExportException("The export output already exists or is a symbolic link.")
        }
        return target
    }

    private fun validateDirectoryChain(directory: Path) {
        var current = directory.root ?: throw LogExportException("The export output path is invalid.")
        directory.iterator().forEach { part ->
            current = current.resolve(part)
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                throw LogExportException("The export output parent must be an existing non-symlink directory.")
            }
        }
    }

    private fun createPrivateTemporary(parent: Path): Path {
        val path = parent.resolve(".reprodroid-log-export-${UUID.randomUUID()}.part")
        val options: Set<OpenOption> = setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        val attributes = if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            emptyArray()
        }
        try {
            FileChannel.open(path, options, *attributes).use { it.force(true) }
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(path) }
            throw LogExportException("The export temporary file could not be created safely.", failure)
        }
        return path
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        writeFully(channel, ByteBuffer.wrap(bytes))
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "LOG_EXPORT_WRITE_FAILED" }
    }

    private fun requireLocalJob(store: SQLiteJobStore, jobId: String): StoredJob = try {
        store.getStoredJob(jobId)?.takeIf { it.principalId == SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL }
            ?: throw LogExportException("The requested job does not exist for the local principal.")
    } catch (failure: LogExportException) {
        throw failure
    } catch (failure: Throwable) {
        throw LogExportException("The selected job could not be authorized safely.", failure)
    }

    private fun requireCanonicalJobId(jobId: String) {
        val canonical = runCatching { UUID.fromString(jobId).toString() == jobId }.getOrDefault(false)
        if (!UUID_PATTERN.matches(jobId) || !canonical) {
            throw LogExportException("The jobId must be a canonical lowercase UUID.")
        }
    }

    private fun safeHeader(value: String): String = value.replace('\n', ' ').replace('\r', ' ').take(512)

    private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private class BodySpool(parent: Path) : AutoCloseable {
        val path: Path = createPrivateTemporary(parent)
        private val channel: FileChannel
        private var closed = false

        init {
            try {
                channel = FileChannel.open(path, WRITE, NOFOLLOW_LINKS)
            } catch (failure: Throwable) {
                runCatching { Files.deleteIfExists(path) }
                throw LogExportException("The export body temporary file could not be opened safely.", failure)
            }
        }
        var bytes: Long = 0
            private set
        var isFull: Boolean = false
            private set
        val remaining: Long get() = BODY_LIMIT - bytes

        fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!closed) { "LOG_EXPORT_BODY_CLOSED" }
            if (length == 0 || isFull) return
            val count = minOf(length.toLong(), remaining).toInt()
            val value = java.nio.ByteBuffer.wrap(buffer, offset, count)
            while (value.hasRemaining()) check(channel.write(value) > 0) { "LOG_EXPORT_BODY_WRITE_FAILED" }
            bytes += count
            if (bytes >= BODY_LIMIT) isFull = true
        }

        fun tryWrite(value: ByteArray): Boolean {
            if (value.size.toLong() > remaining) {
                isFull = true
                return false
            }
            write(value, 0, value.size)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                channel.force(true)
            } finally {
                channel.close()
            }
        }

        fun closeAndDelete() {
            runCatching { close() }
            runCatching { Files.deleteIfExists(path) }
        }
    }
}
