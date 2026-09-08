package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Durable Phase 4.6 pairing/authentication tests.
 *
 * The tests use a real SQLite database and the real RunnerSecurityStore.  They
 * intentionally inspect only hashes and state, never printing invitation or
 * bearer secrets.  Network/TLS product evidence is kept in a separate test so
 * these state-machine tests remain deterministic and fast.
 */
class RunnerSecurityStoreTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun invitationStoresOnlySecretHashAndKeepsRunnerIdentityStableAcrossRestart() {
        val security = newSecurity()
        val invitation = security.openInvitation(ENDPOINT, ROOT_PIN)
        val hash = sqlString("SELECT secret_sha256 FROM pairing_invitations WHERE invitation_id = '${invitation.invitationId}'")

        assertEquals(hexSha256(invitation.invitationSecret), hash)
        assertNotEquals(invitation.invitationSecret, hash)
        assertEquals("OPEN", sqlString("SELECT state FROM pairing_invitations WHERE invitation_id = '${invitation.invitationId}'"))
        assertEquals(invitation.runnerId, security.runnerId())
        assertEquals(invitation.runnerId, newSecurity().runnerId())
        assertTrue(invitation.invitationSecret.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertFalse(databaseText().contains(invitation.invitationSecret))
    }

    @Test
    fun pairingApprovalCreatesOnePrincipalAndAuthenticatesOnlyTheCompleteBearer() {
        val security = newSecurity()
        val pairing = createPending(security, "device-a")

        assertEquals("PENDING_APPROVAL", pairing.pending.state)
        assertEquals(null, pairing.pending.principalId)
        val approved = security.decide(pairing.pending.requestId, approve = true)
        val principalId = requireNotNull(approved.principalId)

        assertEquals("APPROVED", approved.state)
        assertTrue(principalId.matches(UUID_REGEX.toRegex()))
        assertNotEquals(SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL, principalId)
        assertEquals(principalId, security.authenticate("Bearer ${pairing.token}"))
        assertApiFailure(HttpStatusCode.Unauthorized) {
            security.authenticate("Bearer ${pairing.token.dropLast(1)}0")
        }
        assertEquals(pairing.tokenId, sqlString("SELECT token_id FROM credentials WHERE principal_id = '$principalId'"))
        assertEquals(hexSha256(pairing.token), sqlString("SELECT token_sha256 FROM credentials WHERE principal_id = '$principalId'"))
        assertFalse(databaseText().contains(pairing.token))
    }

    @Test
    fun duplicateCreateAfterResponseLossIsIdempotentButDifferentCredentialsCannotReuseInvitation() {
        val security = newSecurity()
        val pairing = createPending(security, "device-a")

        val duplicate = security.createRequest(pairing.request, "127.0.0.1")
        assertEquals(pairing.pending.requestId, duplicate.requestId)
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM pairing_requests"))

        val conflicting = pairing.request.copy(
            tokenId = UUID.randomUUID().toString(),
            tokenSha256 = hexSha256("rdb1.${UUID.randomUUID()}.${"A".repeat(43)}"),
        )
        assertApiFailure(HttpStatusCode.Unauthorized) {
            security.createRequest(conflicting, "127.0.0.1")
        }
        assertEquals("CONSUMED", sqlString("SELECT state FROM pairing_invitations"))
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM principals WHERE kind = 'PAIRED'"))
    }

    @Test
    fun fiveInvalidInvitationSecretsLockWithoutChangingTheStoredHash() {
        val security = newSecurity()
        val pairing = createPendingInput(security, "device-a")
        val storedHash = sqlString("SELECT secret_sha256 FROM pairing_invitations WHERE invitation_id = '${pairing.invitation.invitationId}'")

        // Build valid-shaped but incorrect secrets so the attempt counter is
        // reached instead of being rejected by request-shape validation.
        repeat(5) {
            val invalid = pairing.request.copy(invitationSecret = "B".repeat(42) + "A")
            assertApiFailure(HttpStatusCode.Unauthorized) {
                security.createRequest(invalid, "127.0.0.1")
            }
        }
        assertEquals("LOCKED", sqlString("SELECT state FROM pairing_invitations"))
        assertEquals(5, sqlLong("SELECT invalid_attempts FROM pairing_invitations"))
        assertEquals(storedHash, sqlString("SELECT secret_sha256 FROM pairing_invitations"))
        assertApiFailure(HttpStatusCode.Unauthorized) {
            security.createRequest(pairing.request, "127.0.0.1")
        }
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM pairing_requests"))
    }

    @Test
    fun expiredInvitationRemainsTerminalAfterRestartAndCannotExtendRequestLifetime() {
        val clock = MutableClock(Instant.parse("2026-09-08T00:00:00Z"))
        val security = newSecurity(clock)
        val pairing = createPendingInput(security, "device-a")
        clock.advance(Duration.ofMinutes(6))

        assertApiFailure(HttpStatusCode.Unauthorized) {
            security.createRequest(pairing.request, "127.0.0.1")
        }
        assertEquals("EXPIRED", sqlString("SELECT state FROM pairing_invitations"))
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM pairing_requests"))

        val restarted = newSecurity(clock)
        assertEquals("EXPIRED", sqlString("SELECT state FROM pairing_invitations"))
        assertEquals(pairing.invitation.expiresAt, sqlString("SELECT expires_at FROM pairing_invitations"))
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM pairing_requests"))
        assertEquals(restarted.runnerId(), security.runnerId())
    }

    @Test
    fun pendingStatusRequiresMatchingContinuationAndRestartDoesNotApproveIt() {
        val security = newSecurity()
        val pairing = createPending(security, "device-a")

        assertApiFailure(HttpStatusCode.Unauthorized) {
            security.status(pairing.pending.requestId, continuationHeader(pairing, "C".repeat(43)))
        }
        val pending = security.status(pairing.pending.requestId, continuationHeader(pairing))
        assertEquals("PENDING_APPROVAL", pending.state)
        assertEquals(null, pending.principalId)

        val restarted = newSecurity()
        assertEquals("PENDING_APPROVAL", restarted.status(pairing.pending.requestId, continuationHeader(pairing)).state)
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM principals WHERE kind = 'PAIRED'"))
    }

    @Test
    fun rejectedRequestIsTerminalAndDoesNotActivateBearer() {
        val security = newSecurity()
        val pairing = createPending(security, "device-a")

        val rejected = security.decide(pairing.pending.requestId, approve = false)
        val repeated = security.decide(pairing.pending.requestId, approve = false)
        assertEquals("REJECTED", rejected.state)
        assertEquals(rejected, repeated)
        assertApiFailure(HttpStatusCode.Unauthorized) { security.authenticate("Bearer ${pairing.token}") }
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM credentials"))
        assertEquals("REJECTED", security.status(pairing.pending.requestId, continuationHeader(pairing)).state)
    }

    @Test
    fun approvalFailureRollsBackPrincipalCredentialAndRequestState() {
        val security = newSecurity()
        val first = createPending(security, "device-a")
        val firstApproved = security.decide(first.pending.requestId, approve = true)
        assertNotNull(firstApproved.principalId)
        val principalsBefore = sqlLong("SELECT COUNT(*) FROM principals WHERE kind = 'PAIRED'")

        val second = createPendingInput(security, "device-b")
        val conflictingTokenId = UUID.randomUUID().toString()
        val conflictingToken = "rdb1.$conflictingTokenId.${"C".repeat(43)}"
        // Seed a credential row that is not referenced by any pairing request.
        // The request can then be created, while approval fails at the
        // credential insert and must roll back its newly-created principal.
        sqlUpdate(
            "INSERT INTO credentials(token_id,token_sha256,principal_id,created_at,state) " +
                "VALUES ('$conflictingTokenId','${hexSha256(conflictingToken)}','${SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL}'," +
                "'2026-09-08T00:00:00Z','ACTIVE')",
        )
        val conflicting = second.request.copy(
            tokenId = conflictingTokenId,
            tokenSha256 = hexSha256(conflictingToken),
        )
        // The invitation was not consumed by the original request yet.  Use
        // the valid invitation secret and conflicting token to leave a
        // pending request before the approval-time failure.
        val pending = security.createRequest(conflicting, "127.0.0.2")
        assertEquals("PENDING_APPROVAL", pending.state)
        assertThrows(Exception::class.java) { security.decide(pending.requestId, approve = true) }

        assertEquals(principalsBefore, sqlLong("SELECT COUNT(*) FROM principals WHERE kind = 'PAIRED'"))
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM principals WHERE display_name = 'device-b'"))
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM credentials WHERE token_id = '$conflictingTokenId'"))
        assertEquals("PENDING_APPROVAL", sqlString("SELECT state FROM pairing_requests WHERE request_id = '${pending.requestId}'"))
        assertEquals("CONSUMED", sqlString("SELECT state FROM pairing_invitations WHERE invitation_id = '${second.invitation.invitationId}'"))
    }

    @Test
    fun malformedUnknownAndRevokedBearersHaveUniformUnauthorizedResults() {
        val security = newSecurity()
        val pairing = createPending(security, "device-a")
        security.decide(pairing.pending.requestId, approve = true)

        val failures = listOf(
            null,
            "",
            "Bearer",
            "Basic ${pairing.token}",
            "Bearer unknown",
            "Bearer rdb1.${pairing.tokenId}.not-enough",
            "Bearer ${pairing.token.dropLast(1)}B",
        ).map { header -> assertApiFailure(HttpStatusCode.Unauthorized) { security.authenticate(header) } }
        assertTrue(failures.map { it.code }.toSet().size == 1)
        assertTrue(failures.map { it.message }.toSet().size == 1)

        val principal = security.authenticate("Bearer ${pairing.token}")
        security.revoke(principal)
        val revoked = assertApiFailure(HttpStatusCode.Unauthorized) { security.authenticate("Bearer ${pairing.token}") }
        assertEquals(failures.first().code, revoked.code)
        assertEquals("REVOKED", sqlString("SELECT state FROM credentials WHERE token_id = '${pairing.tokenId}'"))
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM revocation_events WHERE principal_id = '$principal'"))
    }

    @Test
    fun developmentPrincipalCannotBeRevokedAndRePairingCreatesNewPrincipal() {
        val security = newSecurity()
        assertThrows(IllegalArgumentException::class.java) { security.revoke(SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) }

        val first = createPending(security, "device-a")
        val firstPrincipal = requireNotNull(security.decide(first.pending.requestId, true).principalId)
        security.revoke(firstPrincipal)

        val second = createPending(security, "device-a")
        val secondPrincipal = requireNotNull(security.decide(second.pending.requestId, true).principalId)
        assertNotEquals(firstPrincipal, secondPrincipal)
        assertApiFailure(HttpStatusCode.Unauthorized) { security.authenticate("Bearer ${first.token}") }
        assertEquals(secondPrincipal, security.authenticate("Bearer ${second.token}"))
    }

    @Test
    fun rateLimitReturnsSameBoundedFailureAfterTenAttemptsPerSource() {
        val security = newSecurity()
        val failures = (0..10).map { index ->
            if (index < 10) {
                assertDoesNotThrow { security.requireMutationAllowed("198.51.100.9") }
                null
            } else {
                assertApiFailure(HttpStatusCode.TooManyRequests) {
                    security.requireMutationAllowed("198.51.100.9")
                }
            }
        }.filterNotNull()
        assertEquals(1, failures.map { it.code }.toSet().size)
        assertEquals(1, failures.map { it.message }.toSet().size)
    }

    @Test
    fun concurrentDuplicateCreatesConvergeToOneRequestAndOneConsumedInvitation() {
        val security = newSecurity()
        val pairing = createPendingInput(security, "device-a")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(
                listOf(
                    Callable { security.createRequest(pairing.request, "127.0.0.1") },
                    Callable { security.createRequest(pairing.request, "127.0.0.1") },
                ),
            ).map { it.get() }
            assertEquals(setOf(pairing.invitation.runnerId), results.map { it.runnerId }.toSet())
            assertEquals(setOf(results.first().requestId), results.map { it.requestId }.toSet())
            assertEquals(1, sqlLong("SELECT COUNT(*) FROM pairing_requests"))
            assertEquals("CONSUMED", sqlString("SELECT state FROM pairing_invitations"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun pendingExpiryPreservesOriginalDeadlineAndCannotBeApprovedAfterRestart() {
        val clock = MutableClock(Instant.parse("2026-09-08T00:00:00Z"))
        val security = newSecurity(clock)
        val input = createPendingInput(security, "late device")
        clock.advance(Duration.ofMinutes(4))
        val pending = security.createRequest(input.request, "127.0.0.1")
        assertEquals(input.invitation.expiresAt, pending.expiresAt)
        clock.advance(Duration.ofMinutes(1))
        val restarted = newSecurity(clock)
        assertEquals("EXPIRED", restarted.status(pending.requestId, continuationHeader(input)).state)
        assertEquals("EXPIRED", restarted.decide(pending.requestId, true).state)
        assertEquals(0, sqlLong("SELECT count(*) FROM credentials"))
    }

    @Test
    fun fractionalExpiryUsesTimeOrderingRatherThanTimestampTextOrdering() {
        val clock = MutableClock(Instant.parse("2026-09-08T00:00:00.100Z"))
        val security = newSecurity(clock)
        val input = createPendingInput(security, "fractional expiry device")
        clock.advance(Duration.ofMillis(299_900))
        val pending = security.createRequest(input.request, "127.0.0.1")
        assertEquals("PENDING_APPROVAL", pending.state)
        assertEquals(Instant.parse("2026-09-08T00:05:00.100Z"), Instant.parse(pending.expiresAt))
        clock.advance(Duration.ofMillis(100))
        assertEquals("EXPIRED", security.status(pending.requestId, continuationHeader(input)).state)
        assertEquals(0, sqlLong("SELECT count(*) FROM credentials"))
    }

    @Test
    fun invitationCapacityAndGlobalRateWindowHaveExactBoundaries() {
        val clock = MutableClock(Instant.parse("2026-09-08T00:00:00Z"))
        val security = newSecurity(clock)
        repeat(8) { security.openInvitation(ENDPOINT, ROOT_PIN) }
        assertThrows(IllegalStateException::class.java) { security.openInvitation(ENDPOINT, ROOT_PIN) }
        repeat(30) { index -> security.requireMutationAllowed("192.0.2.${index + 1}") }
        assertApiFailure(HttpStatusCode.TooManyRequests) { security.requireMutationAllowed("198.51.100.1") }
        clock.advance(Duration.ofMinutes(1))
        assertDoesNotThrow { security.requireMutationAllowed("198.51.100.1") }
        clock.advance(Duration.ofMinutes(4))
        security.openInvitation(ENDPOINT, ROOT_PIN)
        assertEquals(8, sqlLong("SELECT count(*) FROM pairing_invitations WHERE state='EXPIRED'"))
        assertEquals(1, sqlLong("SELECT count(*) FROM pairing_invitations WHERE state='OPEN'"))
    }

    @Test
    fun rootReplacementRevokesPrincipalsAndInvalidatesAllOldPendingAuthority() {
        val security = newSecurity()
        val approvedInput = createPending(security, "active device")
        val principal = requireNotNull(security.decide(approvedInput.pending.requestId, true).principalId)
        val pendingInput = createPending(security, "pending device")
        val unused = createPendingInput(security, "unused invitation")

        security.replaceRootRevocation()

        assertApiFailure(HttpStatusCode.Unauthorized) { security.authenticate("Bearer ${approvedInput.token}") }
        assertEquals("REVOKED", sqlString("SELECT state FROM principals WHERE principal_id='$principal'"))
        assertEquals("FAILED", security.status(pendingInput.pending.requestId, continuationHeader(pendingInput)).state)
        assertEquals("FAILED", security.decide(pendingInput.pending.requestId, true).state)
        assertApiFailure(HttpStatusCode.Unauthorized) { security.createRequest(unused.request, "127.0.0.1") }
        assertEquals("LOCKED", sqlString("SELECT state FROM pairing_invitations WHERE invitation_id='${unused.invitation.invitationId}'"))
        assertEquals("ROOT_REPLACEMENT", sqlString("SELECT actor_kind FROM revocation_events WHERE principal_id='$principal'"))
        assertEquals(1, sqlLong("SELECT count(*) FROM principals WHERE kind='PAIRED'"))
    }

    @Test
    fun cancelResponseForAlreadyApprovedRequestDoesNotClaimRevocation() {
        val security = newSecurity()
        val input = createPending(security, "approved during cancellation")
        val approved = security.decide(input.pending.requestId, true)
        security.cancel(input.pending.requestId, continuationHeader(input))
        assertEquals("APPROVED", security.status(input.pending.requestId, continuationHeader(input)).state)
        assertEquals(approved.principalId, security.authenticate("Bearer ${input.token}"))
        assertEquals(0, sqlLong("SELECT count(*) FROM revocation_events"))
    }

    private fun newSecurity(clock: Clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC)): RunnerSecurityStore {
        SQLiteJobStore(stateDirectory, clock)
        return RunnerSecurityStore(stateDirectory, clock)
    }

    private fun createPending(security: RunnerSecurityStore, displayName: String): PairingFixture {
        val input = createPendingInput(security, displayName)
        val pending = security.createRequest(input.request, "127.0.0.1")
        return PairingFixture(input.invitation, input.request, pending, input.tokenId, input.token, input.continuationSecret)
    }

    private fun createPendingInput(security: RunnerSecurityStore, displayName: String): PairingInput {
        val invitation = security.openInvitation(ENDPOINT, ROOT_PIN)
        val tokenId = UUID.randomUUID().toString()
        val continuationId = UUID.randomUUID().toString()
        val token = "rdb1.$tokenId.${"A".repeat(43)}"
        val continuationSecret = "B".repeat(42) + "A"
        val request = requestFor(
            invitationId = invitation.invitationId,
            invitationSecret = invitation.invitationSecret,
            tokenId = tokenId,
            continuationId = continuationId,
            runnerId = invitation.runnerId,
            tokenSha256 = hexSha256(token),
            continuationSha256 = hexSha256(continuationSecret),
            deviceDisplayName = displayName,
        )
        return PairingInput(invitation, request, tokenId, token, continuationSecret)
    }

    private fun requestFor(
        invitationId: String,
        invitationSecret: String,
        tokenId: String,
        continuationId: String,
        runnerId: String = UUID.randomUUID().toString(),
        tokenSha256: String = hexSha256("rdb1.$tokenId.${"A".repeat(43)}"),
        continuationSha256: String = hexSha256("B".repeat(42) + "A"),
        deviceDisplayName: String = "device",
    ) = PairingCreateRequest(
        schemaVersion = 1,
        runnerId = runnerId,
        invitationId = invitationId,
        invitationSecret = invitationSecret,
        deviceDisplayName = deviceDisplayName,
        tokenId = tokenId,
        tokenSha256 = tokenSha256,
        continuationId = continuationId,
        continuationSha256 = continuationSha256,
    )

    private fun continuationHeader(pairing: PairingFixture, secret: String = pairing.continuationSecret): String =
        "ReproDroid-Continuation ${pairing.request.continuationId}.$secret"

    private fun continuationHeader(pairing: PairingInput, secret: String = pairing.continuationSecret): String =
        "ReproDroid-Continuation ${pairing.request.continuationId}.$secret"

    private fun assertApiFailure(expected: HttpStatusCode, block: () -> Unit): ApiException {
        val failure = assertThrows(ApiException::class.java, block)
        assertEquals(expected, failure.status)
        return failure
    }

    private fun sqlLong(sql: String): Long = DriverManager.getConnection(dbUrl()).use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getLong(1) } }
    }

    private fun sqlUpdate(sql: String) {
        DriverManager.getConnection(dbUrl()).use { connection ->
            connection.createStatement().use { statement -> statement.executeUpdate(sql) }
        }
    }

    private fun sqlString(sql: String): String = DriverManager.getConnection(dbUrl()).use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) } }
    }

    private fun databaseText(): String = DriverManager.getConnection(dbUrl()).use { connection ->
        val tables = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
        buildString { tables.forEach { table ->
            check(table.matches(Regex("[a-z_]+")))
            connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM $table").use { rows ->
                while (rows.next()) (1..rows.metaData.columnCount).forEach { column -> append(rows.getString(column)).append('\n') }
            } }
        } }
    }

    private fun dbUrl() = "jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}"

    private data class PairingInput(
        val invitation: PairingInvitationPayload,
        val request: PairingCreateRequest,
        val tokenId: String,
        val token: String,
        val continuationSecret: String,
    )

    private data class PairingFixture(
        val invitation: PairingInvitationPayload,
        val request: PairingCreateRequest,
        val pending: PairingStatusResponse,
        val tokenId: String,
        val token: String,
        val continuationSecret: String,
    )

    private class MutableClock(initial: Instant) : Clock() {
        @Volatile
        private var current = initial

        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    private companion object {
        const val ENDPOINT = "https://runner.example:8443"
        const val ROOT_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    }
}
