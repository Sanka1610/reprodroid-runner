package com.sanka1610.reprodroid.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Runner-side ownership boundary tests for retention resources.
 *
 * Two real persisted principals and jobs are used.  A principal supplied in a
 * request must not be able to hold, reserve, enumerate, or read another
 * principal's resource merely because the resource ID is known.
 */
class RunnerPrincipalIsolationTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun holdAndReservationCannotTargetAnotherPrincipalsJob() {
        val jobs = seedJobs()
        val retention = StorageRetentionStore(stateDirectory)

        val holdFailure = assertThrows(ApiException::class.java) {
            retention.createHold(OWNER_A, UUID.randomUUID().toString(), holdCommand(jobs.ownerB))
        }
        assertEquals(404, holdFailure.status.value)
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM retention_holds"))
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM operations"))

        val reservationFailure = assertThrows(ApiException::class.java) {
            retention.createReservation(
                OWNER_A,
                UUID.randomUUID().toString(),
                reservationCommand(jobs.ownerB),
            )
        }
        assertEquals(404, reservationFailure.status.value)
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM storage_reservations"))
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM operations"))
    }

    @Test
    fun cleanupPreviewEnumeratesOnlyRequestingPrincipalsResources() {
        val jobs = seedJobs()
        val retention = StorageRetentionStore(stateDirectory)
        val preview = retention.createCleanupPreview(
            OWNER_A,
            UUID.randomUUID().toString(),
            cleanupCommand(),
        ).response
        val previewId = requireNotNull(preview.result).resourceId
        val result = retention.cleanupPreview(previewId, OWNER_A)

        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.all { it.resourceId == jobs.ownerA })
        assertTrue(result.items.none { it.resourceId == jobs.ownerB })

        val otherOwnerFailure = assertThrows(ApiException::class.java) {
            retention.cleanupPreview(previewId, OWNER_B)
        }
        assertEquals(404, otherOwnerFailure.status.value)
    }

    @Test
    fun cleanupResourceIdFilterCannotEscapeOwnerBoundary() {
        val jobs = seedJobs()
        val retention = StorageRetentionStore(stateDirectory)
        val preview = retention.createCleanupPreview(
            OWNER_A,
            UUID.randomUUID().toString(),
            cleanupCommand(resourceIds = setOf(jobs.ownerB)),
        ).response
        val previewId = requireNotNull(preview.result).resourceId
        val result = retention.cleanupPreview(previewId, OWNER_A)

        // A caller may supply a known foreign ID, but it must receive an
        // empty owner-scoped preview instead of a foreign resource candidate.
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun revokedPrincipalCannotAcceptANewJobHoldOrReservationAfterPriorAuthentication() {
        val jobs = seedJobs()
        val store = SQLiteJobStore(stateDirectory)
        val retention = StorageRetentionStore(stateDirectory)
        RunnerSecurityStore(stateDirectory).revoke(OWNER_A)

        val rejectedJob = assertThrows(ApiException::class.java) {
            store.createJob(simulatedRequest(), principalId = OWNER_A)
        }
        assertEquals(401, rejectedJob.status.value)
        val rejectedHold = assertThrows(ApiException::class.java) {
            retention.createHold(OWNER_A, UUID.randomUUID().toString(), holdCommand(jobs.ownerA))
        }
        assertEquals(401, rejectedHold.status.value)
        val rejectedReservation = assertThrows(ApiException::class.java) {
            retention.createReservation(OWNER_A, UUID.randomUUID().toString(), reservationCommand(jobs.ownerA))
        }
        assertEquals(401, rejectedReservation.status.value)
        assertEquals(2, sqlLong("SELECT count(*) FROM jobs"))
        assertEquals(0, sqlLong("SELECT count(*) FROM operations"))
        assertEquals(0, sqlLong("SELECT count(*) FROM retention_holds"))
        assertEquals(0, sqlLong("SELECT count(*) FROM storage_reservations"))
        assertEquals(2, sqlLong("SELECT count(*) FROM jobs WHERE state='SUCCEEDED'"))
    }

    @Test
    fun revokedPrincipalCannotReadACompletedIdempotencyReplayAtDomainAcceptance() {
        val jobs = seedJobs()
        val retention = StorageRetentionStore(stateDirectory)
        val key = UUID.randomUUID().toString()
        val command = holdCommand(jobs.ownerA)
        retention.createHold(OWNER_A, key, command)
        assertEquals(1, sqlLong("SELECT count(*) FROM operations"))
        RunnerSecurityStore(stateDirectory).revoke(OWNER_A)

        val rejected = assertThrows(ApiException::class.java) { retention.createHold(OWNER_A, key, command) }
        assertEquals(401, rejected.status.value)
        assertEquals(1, sqlLong("SELECT count(*) FROM operations"))
        assertEquals(1, sqlLong("SELECT count(*) FROM retention_holds"))
    }

    private fun seedJobs(): Jobs {
        val store = SQLiteJobStore(stateDirectory)
        listOf(OWNER_A, OWNER_B).forEach { owner ->
            sqlUpdate("INSERT INTO principals(principal_id,kind,display_name,state,created_at) VALUES('$owner','PAIRED','test owner','ACTIVE','2026-09-08T00:00:00Z')")
        }
        val ownerA = store.createJob(
            simulatedRequest(),
            principalId = OWNER_A,
        ).jobId
        val ownerB = store.createJob(
            simulatedRequest(),
            principalId = OWNER_B,
        ).jobId
        val old = Instant.now().minus(120, ChronoUnit.DAYS).toString()
        sqlUpdate("UPDATE jobs SET state = 'SUCCEEDED', progress_percent = 100, updated_at = '$old'")
        listOf(ownerA, ownerB).forEach { jobId ->
            val workspace = stateDirectory.resolve("workspaces").resolve(jobId)
            Files.createDirectories(workspace)
            Files.writeString(workspace.resolve("owner-data.txt"), jobId)
        }
        return Jobs(ownerA, ownerB)
    }

    private fun holdCommand(jobId: String): HoldCreateCommand = HoldCreateCommand(
        resourceKind = RetentionResourceKind.JOB,
        resourceId = jobId,
        reason = "CURRENT_COMPARISON",
        clientReferenceType = "COMPARISON",
        clientReferenceId = UUID.randomUUID().toString(),
        normalizedRequest = JsonObject(emptyMap()),
    )

    private fun reservationCommand(jobId: String): ReservationCreateCommand = ReservationCreateCommand(
        area = StorageArea.RUNNER_JOB,
        purpose = "CLIENT_OPERATION",
        resourceKind = RetentionResourceKind.JOB,
        resourceId = jobId,
        requestedBytes = 1,
        normalizedRequest = JsonObject(emptyMap()),
    )

    private fun cleanupCommand(resourceIds: Set<String> = emptySet()): CleanupPreviewCommand = CleanupPreviewCommand(
        area = StorageArea.RUNNER_JOB,
        resourceKinds = setOf(CleanupResourceKind.JOB_WORKSPACE),
        eligibleBefore = Instant.now().toString(),
        resourceIds = resourceIds,
        normalizedRequest = buildJsonObject {
            put("test", "owner-scoped-cleanup")
            put("resourceIdCount", resourceIds.size)
        },
    )

    private fun simulatedRequest() = CreateJobRequest(
        executionMode = ExecutionMode.SIMULATED,
        repositoryUrl = "https://github.com/example/app.git",
        revision = RequestedRevision(RevisionType.BRANCH, "main"),
        simulationOutcome = SimulationOutcome.SUCCESS,
    )

    private fun sqlUpdate(sql: String) {
        DriverManager.getConnection(dbUrl()).use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    private fun sqlLong(sql: String): Long = DriverManager.getConnection(dbUrl()).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun dbUrl() = "jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}"

    private data class Jobs(val ownerA: String, val ownerB: String)

    private companion object {
        const val OWNER_A = "00000000-0000-4000-8000-0000000000aa"
        const val OWNER_B = "00000000-0000-4000-8000-0000000000bb"
    }
}
