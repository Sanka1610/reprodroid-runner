package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.io.path.writeText

class LogExportTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `runner operational log is prepared as utf8 owner-only file`() {
        RunnerLogging.prepare(stateDirectory)
        val log = RunnerLogging.operationalLogPath(stateDirectory)
        Files.writeString(log, "警告\n", Charsets.UTF_8)

        assertTrue(Files.isRegularFile(log))
        assertEquals("警告\n", Files.readString(log))
        if (Files.getFileStore(stateDirectory).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixPermissions.permissions(log))
        }
    }

    @Test
    fun `fresh store creates owner-only state before dependency logging`() {
        val freshState = stateDirectory.resolve("missing-state")

        val store = SQLiteJobStore(freshState)
        val job = store.createJob(simulatedRequest()).jobId

        assertTrue(Files.isDirectory(freshState))
        assertTrue(Files.isDirectory(freshState.resolve("logs")))
        if (Files.getFileStore(freshState).supportsFileAttributeView("posix")) {
            assertEquals("rwx------", PosixPermissions.permissions(freshState))
            assertEquals("rwx------", PosixPermissions.permissions(freshState.resolve("logs")))
            assertEquals(
                "rw-------",
                PosixPermissions.permissions(RunnerLogging.operationalLogPath(freshState)),
            )
            assertEquals("rw-------", PosixPermissions.permissions(freshState.resolve("logs/$job.log")))
        }
    }

    @Test
    fun `runner operational log rejects a symbolic link`() {
        val target = stateDirectory.resolve("outside.log").also { it.writeText("keep") }
        Files.createSymbolicLink(stateDirectory.resolve(RunnerLogging.OPERATIONAL_LOG_FILE), target)

        assertThrows(IllegalStateException::class.java) { RunnerLogging.prepare(stateDirectory) }
        assertEquals("keep", Files.readString(target))
    }

    @Test
    fun `store rejects a symbolic link for the job log directory`() {
        val target = Files.createDirectory(stateDirectory.resolve("outside-logs"))
        Files.createSymbolicLink(stateDirectory.resolve("logs"), target)

        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(stateDirectory) }
        assertTrue(Files.list(target).use { paths -> !paths.findAny().isPresent })
    }

    @Test
    fun `job export is utf8 bounded and includes required safety header`() {
        val store = SQLiteJobStore(stateDirectory)
        val job = store.createJob(simulatedRequest()).jobId
        store.appendLog(job, LogLevel.WARN, "日本語のbuild output")
        val outputDirectory = Files.createDirectory(stateDirectory.resolve("exports"))
        val output = outputDirectory.resolve("job.log")

        val result = RunnerLogExporter.exportJobLog(store, stateDirectory, job, output)
        val text = Files.readString(output)

        assertTrue(result.bytes <= RunnerLogExporter.MAX_EXPORT_BYTES)
        assertTrue(text.contains("# format: runner-log-export@1"))
        assertTrue(text.contains("# range: sequence="))
        assertTrue(text.contains("# missing: false"))
        assertTrue(text.contains("# truncated: false"))
        assertTrue(text.contains("# WARNING: build output is not anonymized"))
        assertTrue(text.contains("日本語のbuild output"))
    }

    @Test
    fun `job export refuses another principal and leaves no output`() {
        val store = SQLiteJobStore(stateDirectory)
        val job = store.createJob(simulatedRequest()).jobId
        DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.prepareStatement("UPDATE jobs SET principal_id = ? WHERE job_id = ?").use { statement ->
                statement.setString(1, "paired-principal")
                statement.setString(2, job)
                assertTrue(statement.executeUpdate() == 1)
            }
        }
        val output = stateDirectory.resolve("not-created.log")

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportJobLog(store, stateDirectory, job, output)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `missing job file is explicit in the exported header`() {
        val store = SQLiteJobStore(stateDirectory)
        val job = store.createJob(simulatedRequest()).jobId
        Files.delete(stateDirectory.resolve("logs/$job.log"))
        val output = stateDirectory.resolve("missing.log")

        val result = RunnerLogExporter.exportJobLog(store, stateDirectory, job, output)
        val text = Files.readString(output)

        assertTrue(result.missing)
        assertFalse(result.truncated)
        assertTrue(text.contains("# missing: true"))
        assertTrue(text.contains("unavailable"))
    }

    @Test
    fun `job export rejects a symbolic link source and preserves its target`() {
        val store = SQLiteJobStore(stateDirectory)
        val job = store.createJob(simulatedRequest()).jobId
        val source = stateDirectory.resolve("logs/$job.log")
        Files.delete(source)
        val target = stateDirectory.resolve("outside-job.log").also { it.writeText("keep") }
        Files.createSymbolicLink(source, target)
        val output = stateDirectory.resolve("not-created-job-export.log")

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportJobLog(store, stateDirectory, job, output)
        }
        assertFalse(Files.exists(output))
        assertEquals("keep", Files.readString(target))
        assertThrows(IllegalStateException::class.java) { store.getLogs(job, 0, 10) }
        assertEquals("keep", Files.readString(target))
    }

    @Test
    fun `runner export reads rotated utf8 logs in order`() {
        val rotated = stateDirectory.resolve("runner.log.2026-09-08.0.gz")
        GZIPOutputStream(Files.newOutputStream(rotated)).bufferedWriter(Charsets.UTF_8).use { writer: BufferedWriter ->
            writer.write("old-line\n")
        }
        stateDirectory.resolve("runner.log").writeText("new-line\n", Charsets.UTF_8)
        val output = stateDirectory.resolve("runner.txt")

        val result = RunnerLogExporter.exportRunnerLog(stateDirectory, output)
        val text = Files.readString(output)

        assertFalse(result.missing)
        assertFalse(result.truncated)
        assertTrue(text.indexOf("old-line") < text.indexOf("new-line"))
        assertTrue(text.contains("# range: file=runner.log.2026-09-08.0.gz..runner.log"))
    }

    @Test
    fun `runner export truncates without exceeding 32 mib`() {
        val source = stateDirectory.resolve("runner.log")
        Files.newOutputStream(source).use { output ->
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            var written = 0L
            while (written < RunnerLogExporter.MAX_EXPORT_BYTES + 1024) {
                val count = minOf(chunk.size.toLong(), RunnerLogExporter.MAX_EXPORT_BYTES + 1024 - written).toInt()
                output.write(chunk, 0, count)
                written += count
            }
        }
        val output = stateDirectory.resolve("bounded.txt")

        val result = RunnerLogExporter.exportRunnerLog(stateDirectory, output)

        assertTrue(result.truncated)
        assertTrue(Files.size(output) <= RunnerLogExporter.MAX_EXPORT_BYTES)
        assertTrue(Files.readString(output).contains("# truncated: true"))
    }

    @Test
    fun `runner export truncates only at a valid utf8 character boundary`() {
        val source = stateDirectory.resolve("runner.log")
        Files.newBufferedWriter(source, Charsets.UTF_8).use { writer ->
            val chunk = "界".repeat(8 * 1024)
            var bytes = 0L
            while (bytes <= RunnerLogExporter.MAX_EXPORT_BYTES) {
                writer.write(chunk)
                bytes += chunk.toByteArray(Charsets.UTF_8).size
            }
        }
        val output = stateDirectory.resolve("bounded-utf8.txt")

        val result = RunnerLogExporter.exportRunnerLog(stateDirectory, output)
        val text = Files.readString(output, Charsets.UTF_8)

        assertTrue(result.truncated)
        assertTrue(Files.size(output) <= RunnerLogExporter.MAX_EXPORT_BYTES)
        assertFalse(text.contains('\uFFFD'))
    }

    @Test
    fun `runner export rejects malformed utf8 without publishing`() {
        Files.write(stateDirectory.resolve("runner.log"), byteArrayOf(0x41, 0xC3.toByte(), 0x28))
        val output = stateDirectory.resolve("malformed.txt")

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportRunnerLog(stateDirectory, output)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `runner export rejects more sources than bounded retention can create`() {
        repeat(9) { index ->
            stateDirectory.resolve("runner.log.2026-09-${10 + index}.$index.gz").writeText("unexpected")
        }
        stateDirectory.resolve("runner.log").writeText("active")
        val output = stateDirectory.resolve("too-many.txt")

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportRunnerLog(stateDirectory, output)
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `runner export does not publish after a corrupt rotated source`() {
        stateDirectory.resolve("runner.log.2026-09-08.0.gz").writeText("not gzip")
        stateDirectory.resolve("runner.log").writeText("active\n")
        val output = stateDirectory.resolve("corrupt.txt")

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportRunnerLog(stateDirectory, output)
        }
        assertFalse(Files.exists(output))
        assertTrue(Files.list(stateDirectory).use { paths -> !paths.anyMatch { it.fileName.toString().endsWith(".part") } })
    }

    @Test
    fun `existing and symbolic link outputs are never overwritten`() {
        val store = SQLiteJobStore(stateDirectory)
        val job = store.createJob(simulatedRequest()).jobId
        val existing = stateDirectory.resolve("existing.log")
        existing.writeText("keep")

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportJobLog(store, stateDirectory, job, existing)
        }
        assertTrue(Files.readString(existing) == "keep")

        val sentinel = stateDirectory.resolve("sentinel.log").also { it.writeText("keep-link-target") }
        val link = stateDirectory.resolve("link.log")
        Files.createSymbolicLink(link, sentinel)
        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportJobLog(store, stateDirectory, job, link)
        }
        assertTrue(Files.readString(sentinel) == "keep-link-target")
    }

    @Test
    fun `symbolic link in output parent is rejected`() {
        val parentTarget = Files.createDirectory(stateDirectory.resolve("parent-target"))
        val parentLink = stateDirectory.resolve("parent-link")
        Files.createSymbolicLink(parentLink, parentTarget)

        assertThrows(LogExportException::class.java) {
            RunnerLogExporter.exportRunnerLog(stateDirectory, parentLink.resolve("runner.txt"))
        }
        assertTrue(Files.list(parentTarget).use { paths -> !paths.findAny().isPresent })
    }

    private fun simulatedRequest() = CreateJobRequest(
        executionMode = ExecutionMode.SIMULATED,
        repositoryUrl = "https://github.com/example/reprodroid",
        revision = RequestedRevision(RevisionType.TAG, "v1"),
        simulationOutcome = SimulationOutcome.SUCCESS,
    )

    private object PosixPermissions {
        fun permissions(path: Path): String =
            java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
    }
}
