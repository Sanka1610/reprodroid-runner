package com.sanka1610.reprodroid.runner

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class SandboxImportLimits(
    val entries: Int,
    val regularFiles: Int,
    val fileBytes: Long,
    val totalBytes: Long,
    val timeoutNanos: Long = 120_000_000_000,
) {
    companion object {
        val DEPENDENCIES = SandboxImportLimits(50_000, 20_000, 268_435_456, 8_589_934_592)
        val APK = SandboxImportLimits(10_000, 10_000, 536_870_912, 1_073_741_824)
    }
}

/** Used only after all owned containers have been removed; never a substitute for runtime disk quotas. */
internal class SandboxOutputImport(
    private val root: Path,
    private val limits: SandboxImportLimits,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val started = nanoTime()
    private var entries = 0
    private var files = 0
    private var bytes = 0L

    fun validateDirectory() = guarded { withDirectory(root, missingAllowed = false) {} }

    fun dependencies(): List<ManifestFile> = guarded {
        val result = mutableListOf<ManifestFile>()
        withDirectory(root, missingAllowed = true) { directory ->
            traverse(directory, Path.of("")) { relative, parent, name ->
                val digest = readFile(parent, name) { _, _ -> }
                result += ManifestFile(relative.toString().replace('\\', '/'), digest.first, digest.second)
            }
        }
        result.sortedBy { it.path }
    }

    /** Copies to a controller-owned directory first; subsequent APK checks must read that private copy. */
    fun importApks(destination: Path): List<Pair<Path, ManifestFile>> = guarded {
        val result = mutableListOf<Pair<Path, ManifestFile>>()
        try {
            withDirectory(root, missingAllowed = true) { directory ->
                traverse(directory, Path.of("")) { relative, parent, name ->
                    if (relative.nameCount == 1 && relative.fileName.toString().endsWith(".apk", ignoreCase = true)) {
                        val temporary = Files.createTempFile(destination, "sandbox-apk-", ".partial")
                        try {
                            val digest = Files.newByteChannel(temporary, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { output ->
                                readFile(parent, name) { buffer, count ->
                                    val view = ByteBuffer.wrap(buffer, 0, count)
                                    while (view.hasRemaining()) { deadline(); output.write(view) }
                                }
                            }
                            val stored = temporary.resolveSibling(temporary.fileName.toString().removeSuffix(".partial") + ".apk")
                            Files.move(temporary, stored, ATOMIC_MOVE)
                            result += stored to ManifestFile(relative.toString().replace('\\', '/'), digest.first, digest.second)
                        } finally {
                            Files.deleteIfExists(temporary)
                        }
                    }
                }
            }
            deadline()
            result
        } catch (failure: Throwable) {
            result.forEach { (path, _) -> Files.deleteIfExists(path) }
            throw failure
        }
    }

    fun checkDeadline() = deadline()

    private fun traverse(
        directory: SecureDirectoryStream<Path>,
        relative: Path,
        onFile: (Path, SecureDirectoryStream<Path>, Path) -> Unit,
    ) {
        data class Frame(val directory: SecureDirectoryStream<Path>, val relative: Path, val iterator: Iterator<Path>)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(directory, relative, directory.iterator()))
        try {
            while (stack.isNotEmpty()) {
                deadline()
                val frame = stack.last()
                if (!frame.iterator.hasNext()) {
                    stack.removeLast()
                    if (frame.directory !== directory) frame.directory.close()
                    continue
                }
                if (++entries > limits.entries) exceeded()
                val name = frame.iterator.next().fileName
                val attributes = attributes(frame.directory, name)
                if (attributes.isDirectory) {
                    val nested = frame.directory.newDirectoryStream(name, NOFOLLOW_LINKS)
                    stack.addLast(Frame(nested, frame.relative.resolve(name), nested.iterator()))
                } else if (attributes.isRegularFile) {
                    if (++files > limits.regularFiles) exceeded()
                    onFile(frame.relative.resolve(name), frame.directory, name)
                } else invalid()
            }
        } finally {
            stack.reversed().filter { it.directory !== directory }.forEach { it.directory.close() }
        }
    }

    private fun readFile(
        directory: SecureDirectoryStream<Path>, name: Path, consume: (ByteArray, Int) -> Unit,
    ): Pair<Long, String> {
        val before = attributes(directory, name)
        if (!before.isRegularFile || before.fileKey() == null) invalid()
        if (before.size() > limits.fileBytes) exceeded()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        directory.newByteChannel(name, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                deadline()
                val count = input.read(ByteBuffer.wrap(buffer))
                if (count < 0) break
                size += count
                bytes += count
                if (size > limits.fileBytes || bytes > limits.totalBytes) exceeded()
                digest.update(buffer, 0, count)
                consume(buffer, count)
            }
            if (input.size() != size) invalid()
        }
        val after = attributes(directory, name)
        if (!after.isRegularFile || size != before.size() || size != after.size() ||
            before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime()) invalid()
        return size to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()

    /** Open each ancestor relative to its pinned directory descriptor, including ancestors outside the Job root. */
    private fun withDirectory(path: Path, missingAllowed: Boolean, consume: (SecureDirectoryStream<Path>) -> Unit) {
        val absolute = path.toAbsolutePath()
        if (absolute != absolute.normalize()) invalid()
        val handles = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            val rootHandle = Files.newDirectoryStream(absolute.root)
            if (rootHandle !is SecureDirectoryStream<Path>) { rootHandle.close(); invalid() }
            handles += rootHandle
            for (component in absolute) {
                deadline()
                val previous = handles.last()
                val attributes = try {
                    attributes(previous, component)
                } catch (missing: NoSuchFileException) {
                    if (missingAllowed) return else throw missing
                }
                if (!attributes.isDirectory || attributes.isSymbolicLink) invalid()
                handles += previous.newDirectoryStream(component, NOFOLLOW_LINKS)
            }
            consume(handles.last())
        } finally { handles.asReversed().forEach { it.close() } }
    }

    private fun deadline() { if (nanoTime() - started > limits.timeoutNanos) exceeded() }
    private fun invalid(): Nothing = throw TrustedBuildFailure("SANDBOX_OUTPUT_INVALID", "The sandbox output boundary is invalid.")
    private fun exceeded(): Nothing = throw TrustedBuildFailure("SANDBOX_OUTPUT_LIMIT_EXCEEDED", "The sandbox output exceeds its import limits.")
    private fun <T> guarded(block: () -> T): T = try { block() } catch (failure: TrustedBuildFailure) {
        throw failure
    } catch (_: Exception) { invalid() }
}
