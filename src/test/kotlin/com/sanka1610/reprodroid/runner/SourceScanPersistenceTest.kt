package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SourceScanPersistenceTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `finding result and review are persisted atomically`() {
        val store = SQLiteJobStore(stateDirectory)
        val jobId = createScanningJob(store)
        val checkout = checkout("finding").also {
            it.resolve("build.gradle.kts").writeText("val process = ProcessBuilder(\"true\")")
        }
        val result = SourceScanner().scan(jobId, checkout, COMMIT)

        assertTrue(store.recordSourceScanResult(result))
        val awaiting = requireNotNull(store.getJob(jobId))
        assertEquals(JobState.AWAITING_SCAN_REVIEW, awaiting.state)
        assertEquals(SourceScanStatus.COMPLETED, awaiting.sourceScan?.status)
        assertEquals(false, awaiting.sourceScan?.reviewed)
        assertEquals(result.detail, store.getSourceScan(jobId))
        assertEquals(
            ReviewSourceScanResult.DIGEST_MISMATCH,
            store.reviewSourceScan(jobId, "0".repeat(64)),
        )
        assertEquals(
            ReviewSourceScanResult.SUCCESS,
            store.reviewSourceScan(jobId, result.detail.resultSha256),
        )
        assertEquals(JobState.QUEUED, requireNotNull(store.getJob(jobId)).state)
        assertEquals(true, requireNotNull(store.getJob(jobId)).sourceScan?.reviewed)
        assertEquals(listOf(jobId), store.listResumableSourceScanJobIds())
    }

    @Test
    fun `zero finding result proceeds to wrapper verification`() {
        val store = SQLiteJobStore(stateDirectory)
        val jobId = createScanningJob(store)
        val checkout = checkout("clean").also { it.resolve("App.kt").writeText("class App") }

        assertTrue(store.recordSourceScanResult(SourceScanner().scan(jobId, checkout, COMMIT)))

        val job = requireNotNull(store.getJob(jobId))
        assertEquals(JobState.VERIFYING_WRAPPER, job.state)
        assertEquals(false, job.sourceScan?.requiresReview)
        assertEquals(false, job.sourceScan?.reviewed)
    }

    @Test
    fun `restart preserves awaiting review and reviewed queued jobs`() {
        val store = SQLiteJobStore(stateDirectory)
        val awaitingId = createScanningJob(store)
        val reviewedId = createScanningJob(store)
        val scanningId = createScanningJob(store)
        val checkout = checkout("restart").also {
            it.resolve("build.gradle.kts").writeText("val process = ProcessBuilder(\"true\")")
        }
        val awaiting = SourceScanner().scan(awaitingId, checkout, COMMIT)
        val reviewed = SourceScanner().scan(reviewedId, checkout, COMMIT)
        assertTrue(store.recordSourceScanResult(awaiting))
        assertTrue(store.recordSourceScanResult(reviewed))
        assertEquals(ReviewSourceScanResult.SUCCESS, store.reviewSourceScan(reviewedId, reviewed.detail.resultSha256))

        val interrupted = store.markRunningJobsInterrupted()

        assertTrue(scanningId in interrupted)
        assertFalse(awaitingId in interrupted)
        assertFalse(reviewedId in interrupted)
        assertEquals(JobState.AWAITING_SCAN_REVIEW, requireNotNull(store.getJob(awaitingId)).state)
        assertEquals(JobState.QUEUED, requireNotNull(store.getJob(reviewedId)).state)
        assertEquals(JobState.INTERRUPTED, requireNotNull(store.getJob(scanningId)).state)
    }

    @Test
    fun `stored canonical evidence rejects finding row tampering`() {
        val store = SQLiteJobStore(stateDirectory)
        val jobId = createScanningJob(store)
        val checkout = checkout("tamper").also {
            it.resolve("build.gradle.kts").writeText("val process = ProcessBuilder(\"true\")")
        }
        assertTrue(store.recordSourceScanResult(SourceScanner().scan(jobId, checkout, COMMIT)))
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                "UPDATE source_scan_findings SET display_path = ? WHERE job_id = ? AND ordinal = 0",
            ).use { statement ->
                statement.setString(1, "changed.gradle.kts")
                statement.setString(2, jobId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val failure = assertThrows(TrustedBuildFailure::class.java) { store.getSourceScan(jobId) }

        assertEquals("SOURCE_SCAN_INVALID", failure.code)
    }

    @Test
    fun `scan tables survive schema six to seven migration`() {
        val store = SQLiteJobStore(stateDirectory)
        val jobId = store.createJob(request()).jobId
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE source_scan_findings")
                statement.execute("DROP TABLE source_scan_detector_counts")
                statement.execute("DROP TABLE source_scan_results")
                statement.execute("PRAGMA user_version = 6")
            }
        }

        val migrated = SQLiteJobStore(stateDirectory)

        assertNotNull(migrated.getJob(jobId))
        assertEquals(null, migrated.getJob(jobId)?.sourceScan)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(12, statement.executeQuery("PRAGMA user_version").use { rows -> rows.next(); rows.getInt(1) })
            }
        }
    }

    private fun createScanningJob(store: SQLiteJobStore): String = store.createJob(request()).jobId.also { jobId ->
        assertTrue(store.updateState(jobId, JobState.SCANNING_SOURCE, 32))
    }

    private fun request() = CreateJobRequest(
        executionMode = ExecutionMode.REAL_TRUSTED,
        repositoryUrl = "https://github.com/microg/GmsCore.git",
        revision = RequestedRevision(RevisionType.COMMIT, COMMIT),
    )

    private fun checkout(name: String): Path = stateDirectory.resolve(name).also(Path::createDirectories)

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
