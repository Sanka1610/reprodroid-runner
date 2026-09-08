package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal data class ProcessResult(
    val exitCode: Int,
    val outputLines: List<String>,
)

internal class ProcessTimeoutException(message: String) : RuntimeException(message)

internal enum class ProcessFailureStage {
    START,
    OUTPUT_READ,
    AUDIT_APPEND,
    WAIT,
    OUTPUT_DRAIN,
}

internal class ProcessExecutionException(
    val stage: ProcessFailureStage,
    cause: Throwable? = null,
) : RuntimeException("External process failed during ${stage.name}.", cause)

private class OutputCallbackException(cause: Throwable) : RuntimeException(cause)

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
        val process = try {
            processBuilder.start()
        } catch (failure: Throwable) {
            throw ProcessExecutionException(ProcessFailureStage.START, failure)
        }
        val captured = mutableListOf<String>()
        val loggedBytes = AtomicLong(0)
        val readerFailure = AtomicReference<ProcessExecutionException?>()
        val readerDone = CountDownLatch(1)
        val readerThread = thread(name = "reprodroid-process-output", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    readOutput(reader) { rawLine ->
                        val line = rawLine.take(MAX_LOG_LINE_CHARACTERS)
                        if (captureOutput && captured.size < MAX_CAPTURED_LINES) {
                            synchronized(captured) { captured += line }
                        }
                        val newTotal = loggedBytes.addAndGet(line.toByteArray().size.toLong())
                        if (newTotal <= MAX_LOGGED_BYTES) {
                            try {
                                onOutput(line)
                            } catch (failure: Throwable) {
                                throw OutputCallbackException(failure)
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                val callbackFailure = failure as? OutputCallbackException
                readerFailure.compareAndSet(
                    null,
                    ProcessExecutionException(
                        if (callbackFailure == null) ProcessFailureStage.OUTPUT_READ else ProcessFailureStage.AUDIT_APPEND,
                        callbackFailure?.cause ?: failure,
                    ),
                )
                if (process.isAlive) terminateProcessTree(process)
            } finally {
                readerDone.countDown()
            }
        }

        try {
            val completed = try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                throw failure
            } catch (failure: Throwable) {
                throw ProcessExecutionException(ProcessFailureStage.WAIT, failure)
            }
            if (!completed) {
                terminateProcessTree(process)
                throw ProcessTimeoutException("Process exceeded timeout of ${timeout.toMinutes()} minutes.")
            }
            if (!readerDone.await(READER_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                throw ProcessExecutionException(ProcessFailureStage.OUTPUT_DRAIN)
            }
            readerFailure.get()?.let { throw it }
            ProcessResult(process.exitValue(), synchronized(captured) { captured.toList() })
        } finally {
            if (process.isAlive) terminateProcessTree(process)
            if (readerThread.isAlive) runCatching { process.inputStream.close() }
            readerThread.interrupt()
        }
    }

    private fun readOutput(reader: BufferedReader, consume: (String) -> Unit) {
        val line = StringBuilder()
        while (true) {
            val character = reader.read()
            if (character < 0) {
                if (line.isNotEmpty()) consume(line.toString().trimEnd('\r'))
                return
            }
            if (character == '\n'.code) {
                consume(line.toString().trimEnd('\r'))
                line.setLength(0)
            } else if (line.length < MAX_LOG_LINE_CHARACTERS) line.append(character.toChar())
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
