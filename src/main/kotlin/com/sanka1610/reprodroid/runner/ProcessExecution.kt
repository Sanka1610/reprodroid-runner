package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal data class ProcessResult(
    val exitCode: Int,
    val outputLines: List<String>,
)

internal class ProcessTimeoutException(message: String) : RuntimeException(message)

internal interface ProcessExecutor {
    suspend fun execute(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        timeout: Duration,
        captureOutput: Boolean = false,
        onOutput: (String) -> Unit = {},
    ): ProcessResult
}

internal class SystemProcessExecutor : ProcessExecutor {
    override suspend fun execute(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        timeout: Duration,
        captureOutput: Boolean,
        onOutput: (String) -> Unit,
    ): ProcessResult = runInterruptible {
        require(command.isNotEmpty())
        val processBuilder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
        processBuilder.environment().apply {
            clear()
            putAll(environment)
        }
        val process = processBuilder.start()
        val captured = mutableListOf<String>()
        val loggedBytes = AtomicLong(0)
        val readerFailure = arrayOfNulls<Throwable>(1)
        val readerThread = thread(name = "reprodroid-process-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    readOutput(reader) { rawLine ->
                        val line = rawLine.take(MAX_LOG_LINE_CHARACTERS)
                        if (captureOutput && captured.size < MAX_CAPTURED_LINES) {
                            synchronized(captured) { captured += line }
                        }
                        val newTotal = loggedBytes.addAndGet(line.toByteArray().size.toLong())
                        if (newTotal <= MAX_LOGGED_BYTES) onOutput(line)
                    }
                }
            } catch (failure: Throwable) {
                readerFailure[0] = failure
            }
        }

        try {
            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                terminateProcessTree(process)
                throw ProcessTimeoutException("Process exceeded timeout of ${timeout.toMinutes()} minutes.")
            }
            readerThread.join(READER_JOIN_MILLIS)
            readerFailure[0]?.let { throw it }
            ProcessResult(process.exitValue(), synchronized(captured) { captured.toList() })
        } finally {
            if (process.isAlive) terminateProcessTree(process)
            readerThread.interrupt()
        }
    }

    private fun readOutput(reader: BufferedReader, consume: (String) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: return
            consume(line)
        }
    }

    private fun terminateProcessTree(process: Process) {
        val descendants = process.toHandle().descendants().toList().asReversed()
        descendants.forEach(ProcessHandle::destroy)
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            descendants.forEach(ProcessHandle::destroyForcibly)
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val MAX_LOG_LINE_CHARACTERS = 8_192
        const val MAX_CAPTURED_LINES = 100
        const val MAX_LOGGED_BYTES = 16L * 1024 * 1024
        const val READER_JOIN_MILLIS = 5_000L
    }
}
