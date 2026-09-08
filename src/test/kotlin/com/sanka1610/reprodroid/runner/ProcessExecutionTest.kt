package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

class ProcessExecutionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `captures output only after the reader finishes`() = runBlocking {
        val result = SystemProcessExecutor().execute(
            command = listOf("/bin/sh", "-c", "printf 'first\\nsecond\\n'"),
            workingDirectory = temporaryDirectory,
            environment = restrictedEnvironment(),
            timeout = Duration.ofSeconds(5),
            captureOutput = true,
        )

        assertEquals(0, result.exitCode)
        assertEquals(listOf("first", "second"), result.outputLines)
    }

    @Test
    fun `fails closed and retains the audit callback cause`() {
        val callbackFailure = IllegalStateException("synthetic audit failure")

        val failure = assertThrows<ProcessExecutionException> {
            runBlocking {
                SystemProcessExecutor().execute(
                    command = listOf("/bin/sh", "-c", "printf 'line\\n'; sleep 30"),
                    workingDirectory = temporaryDirectory,
                    environment = restrictedEnvironment(),
                    timeout = Duration.ofSeconds(35),
                ) { throw callbackFailure }
            }
        }

        assertEquals(ProcessFailureStage.AUDIT_APPEND, failure.stage)
        assertSame(callbackFailure, failure.cause)
    }

    @Test
    fun `reports process start failures separately`() {
        val failure = assertThrows<ProcessExecutionException> {
            runBlocking {
                SystemProcessExecutor().execute(
                    command = listOf(temporaryDirectory.resolve("missing-command").toString()),
                    workingDirectory = temporaryDirectory,
                    environment = restrictedEnvironment(),
                    timeout = Duration.ofSeconds(5),
                )
            }
        }

        assertEquals(ProcessFailureStage.START, failure.stage)
    }

    private fun restrictedEnvironment(): Map<String, String> = mapOf(
        "HOME" to temporaryDirectory.toString(),
        "PATH" to "/usr/bin:/bin",
    )
}
