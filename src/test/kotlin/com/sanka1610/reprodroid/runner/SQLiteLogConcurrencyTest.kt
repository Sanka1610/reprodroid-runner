package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SQLiteLogConcurrencyTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `separate store instances serialize log sequence allocation before file append`() {
        val first = SQLiteJobStore(stateDirectory)
        val jobId = first.createJob(
            CreateJobRequest(
                executionMode = ExecutionMode.SIMULATED,
                repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
                revision = RequestedRevision(RevisionType.TAG, "6.1.4"),
            ),
        ).jobId
        val second = SQLiteJobStore(stateDirectory)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val workers = listOf(first, second).mapIndexed { index, store ->
            thread(start = true, name = "log-writer-$index") {
                ready.countDown()
                start.await()
                runCatching {
                    repeat(20) { iteration ->
                        store.appendLog(jobId, LogLevel.INFO, "writer-$index-$iteration")
                    }
                }.exceptionOrNull()?.let { failure.compareAndSet(null, it) }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(10_000) }
        assertFalse(workers.any(Thread::isAlive))
        assertNull(failure.get())

        val logs = requireNotNull(first.getLogs(jobId, 0, 100))
        assertEquals(41, logs.entries.size)
        assertEquals((1L..41L).toList(), logs.entries.map(LogEntry::sequence))
        assertEquals(40, logs.entries.count { it.message.startsWith("writer-") })
    }
}
