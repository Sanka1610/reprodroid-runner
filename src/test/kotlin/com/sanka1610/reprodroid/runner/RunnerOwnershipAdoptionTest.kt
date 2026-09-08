package com.sanka1610.reprodroid.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Durable adoption is atomic and explicitly separate from pairing or build authority. */
class RunnerOwnershipAdoptionTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-09-08T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun sameResourceCountWithDifferentJobIdentityInvalidatesPreview() {
        val fixture = fixture()
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        update("UPDATE jobs SET job_id='${UUID.randomUUID()}' WHERE job_id='${fixture.job}'")
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
    }

    @Test
    fun changedRelevantResourceStateInvalidatesPreviewEvenWhenCountsAreUnchanged() {
        val fixture = fixture()
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        update("UPDATE jobs SET state='FAILED' WHERE job_id='${fixture.job}'")
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
    }

    @Test
    fun targetResourceChangeAlsoInvalidatesTheExactReviewedSnapshot() {
        val fixture = fixture()
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        insertOperation(fixture.target)
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
    }

    @Test
    fun activeReviewCleanupAndUnknownJobStatesCannotBeAdopted() {
        val fixture = fixture()
        listOf("BUILDING", "AWAITING_SCAN_REVIEW", "UNKNOWN_FUTURE_STATE").forEach { state ->
            update("UPDATE jobs SET state='$state' WHERE job_id='${fixture.job}'")
            val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
            assertTrue(preview.blockers.isNotEmpty())
            assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
            assertUnchangedAuthority(fixture, preview.previewId)
        }
        update("UPDATE jobs SET state='SUCCEEDED',sandbox_cleanup_status='PENDING' WHERE job_id='${fixture.job}'")
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        assertTrue(preview.blockers.contains("SANDBOX_CLEANUP_PENDING"))
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
    }

    @Test
    fun unresolvedToolchainReconciliationBlocksTheWholeTransfer() {
        val fixture = fixture()
        val operation = insertOperation(SOURCE)
        update("""
            INSERT INTO toolchain_installations(installation_id,operation_id,principal_id,plan_sha256,catalog_sha256,
                request_json,state,progress_percent,created_at,updated_at)
            VALUES('${UUID.randomUUID()}','$operation','$SOURCE','${"a".repeat(64)}','${"b".repeat(64)}',
                '{}','RECONCILIATION_REQUIRED',0,'$now','$now')
        """.trimIndent())
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        assertTrue(preview.blockers.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
    }

    @Test
    fun collidingIdempotencyKeyAndUnknownSchemaRejectWithoutPartialOwnership() {
        val fixture = fixture()
        val key = UUID.randomUUID().toString()
        insertOperation(SOURCE, key)
        insertOperation(fixture.target, key)
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        assertTrue(preview.blockers.contains("IDEMPOTENCY_CONFLICT"))
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
        update("PRAGMA user_version=13")
        assertThrows(IllegalStateException::class.java) { fixture.security.previewAdoption(SOURCE, fixture.target) }
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertEquals(1, number("SELECT count(*) FROM jobs WHERE principal_id='$SOURCE'"))
    }

    @Test
    fun databaseFailureAfterFirstOwnershipUpdateRollsBackResourcesPreviewAndAudit() {
        val fixture = fixture()
        insertOperation(SOURCE)
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        update("""
            CREATE TRIGGER phase46_test_adoption_failure BEFORE UPDATE OF principal_id ON operations
            BEGIN SELECT RAISE(ABORT,'injected ownership write failure'); END
        """.trimIndent())
        assertThrows(Exception::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertUnchangedAuthority(fixture, preview.previewId)
        assertEquals(1, number("SELECT count(*) FROM operations WHERE principal_id='$SOURCE'"))
    }

    @Test
    fun successfulAdoptionTransfersExactCountsOnceWithoutGrantingPairingOrBuildPermission() {
        val fixture = fixture()
        insertOperation(SOURCE)
        val principalsBefore = number("SELECT count(*) FROM principals")
        val credentialsBefore = number("SELECT count(*) FROM credentials")
        val pairingBefore = number("SELECT count(*) FROM pairing_requests")
        val before = text("SELECT state || ':' || requires_confirmation || ':' || execution_mode FROM jobs WHERE job_id='${fixture.job}'")
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        assertTrue(preview.blockers.isEmpty())
        val executed = fixture.security.executeAdoption(preview.previewId)
        assertEquals(preview.counts, executed.counts)
        assertEquals(1, executed.counts.getValue("jobs"))
        assertEquals(1, executed.counts.getValue("operations"))
        assertEquals(fixture.target, text("SELECT principal_id FROM jobs WHERE job_id='${fixture.job}'"))
        assertEquals(before, text("SELECT state || ':' || requires_confirmation || ':' || execution_mode FROM jobs WHERE job_id='${fixture.job}'"))
        assertEquals(principalsBefore, number("SELECT count(*) FROM principals"))
        assertEquals(credentialsBefore, number("SELECT count(*) FROM credentials"))
        assertEquals(pairingBefore, number("SELECT count(*) FROM pairing_requests"))
        val audited = Json.parseToJsonElement(text("SELECT counts_json FROM ownership_adoption_audit WHERE preview_id='${preview.previewId}'")).jsonObject
            .mapValues { it.value.jsonPrimitive.content.toInt() }
        assertEquals(preview.counts, audited)
        assertEquals("EXECUTED", text("SELECT state FROM ownership_adoption_previews WHERE preview_id='${preview.previewId}'"))
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(preview.previewId) }
        assertEquals(1, number("SELECT count(*) FROM ownership_adoption_audit"))
    }

    @Test
    fun expiredPreviewAndTargetRevocationRequireANewExplicitDecision() {
        val fixture = fixture()
        val preview = fixture.security.previewAdoption(SOURCE, fixture.target)
        val later = RunnerSecurityStore(directory, Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC))
        assertThrows(IllegalStateException::class.java) { later.executeAdoption(preview.previewId) }
        assertEquals(1, number("SELECT count(*) FROM jobs WHERE principal_id='$SOURCE'"))
        val fresh = fixture.security.previewAdoption(SOURCE, fixture.target)
        fixture.security.revoke(fixture.target)
        assertThrows(IllegalStateException::class.java) { fixture.security.executeAdoption(fresh.previewId) }
        assertUnchangedAuthority(fixture, fresh.previewId)
    }

    private fun fixture(): Fixture {
        val jobs = SQLiteJobStore(directory, clock)
        val security = RunnerSecurityStore(directory, clock)
        val job = jobs.createJob(CreateJobRequest(
            executionMode = ExecutionMode.SIMULATED, repositoryUrl = "https://github.com/example/fixture",
            revision = RequestedRevision(RevisionType.TAG, "v1"), simulationOutcome = SimulationOutcome.SUCCESS,
        )).jobId
        update("UPDATE jobs SET state='SUCCEEDED',progress_percent=100 WHERE job_id='$job'")
        val invitation = security.openInvitation("https://127.0.0.1:8443", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        val tokenId = UUID.randomUUID().toString()
        val pending = security.createRequest(PairingCreateRequest(
            schemaVersion = 1,
            runnerId = invitation.runnerId, invitationId = invitation.invitationId,
            invitationSecret = invitation.invitationSecret, deviceDisplayName = "Adoption target",
            tokenId = tokenId, tokenSha256 = hexSha256("rdb1.$tokenId.${"A".repeat(43)}"),
            continuationId = UUID.randomUUID().toString(), continuationSha256 = hexSha256("A".repeat(43)),
        ), "127.0.0.1")
        return Fixture(security, job, requireNotNull(security.decide(pending.requestId, true).principalId))
    }

    private fun insertOperation(principal: String, key: String = UUID.randomUUID().toString()): String {
        val id = UUID.randomUUID().toString()
        update("""
            INSERT INTO operations(operation_id,principal_id,operation_kind,idempotency_key,request_sha256,
                contract_id,contract_version,state,created_at,updated_at)
            VALUES('$id','$principal','retention-hold-create','$key','${"a".repeat(64)}','storage-retention',1,'REJECTED','$now','$now')
        """.trimIndent())
        return id
    }

    private fun assertUnchangedAuthority(fixture: Fixture, previewId: String) {
        assertEquals(1, number("SELECT count(*) FROM jobs WHERE principal_id='$SOURCE'"))
        assertEquals(0, number("SELECT count(*) FROM jobs WHERE principal_id='${fixture.target}'"))
        assertEquals("OPEN", text("SELECT state FROM ownership_adoption_previews WHERE preview_id='$previewId'"))
        assertEquals(0, number("SELECT count(*) FROM ownership_adoption_audit"))
    }
    private fun update(sql: String) = DriverManager.getConnection(url()).use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    private fun text(sql: String) = DriverManager.getConnection(url()).use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } } }
    private fun number(sql: String) = text(sql).toInt()
    private fun url() = "jdbc:sqlite:${directory.resolve("reprodroid-runner.sqlite3")}"
    private class Fixture(val security: RunnerSecurityStore, val job: String, val target: String)
    private companion object { const val SOURCE = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL }
}
