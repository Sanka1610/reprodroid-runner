package com.sanka1610.reprodroid.runner

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

/** In-process HTTP routing checks. The engine does not prove TLS or device connectivity. */
class RunnerAuthenticationApiTest {
    @TempDir lateinit var directory: Path

    @Test
    fun everyProtectedRouteAuthenticatesBeforeMalformedIdentifiersHeadersAndBodies() = testApplication {
        application { runnerModule(config()) }
        val responses = mutableSetOf<String>()
        val gets = listOf(
            "/v1/health", "/v1/jobs/malformed", "/v1/jobs/malformed/logs?limit=invalid",
            "/v1/jobs/malformed/artifacts", "/v1/jobs/malformed/build-environment-manifest",
            "/v1/jobs/malformed/source-scan", "/v1/jobs/malformed/artifacts/malformed/content",
            "/v2/capabilities", "/v2/operations/malformed", "/v2/storage/summary",
            "/v2/cleanup/previews/malformed", "/v2/cleanup/runs/malformed", "/v2/builds/malformed",
            "/v2/comparisons/malformed", "/v2/toolchains/catalog", "/v2/toolchains/licenses/malformed",
            "/v2/toolchains/inventory", "/v2/toolchains/installations/malformed",
        )
        val posts = listOf(
            "/v1/jobs", "/v1/jobs/malformed/confirm", "/v1/jobs/malformed/cancel",
            "/v1/jobs/malformed/retry", "/v1/jobs/malformed/source-scan/continue",
            "/v2/retention/holds", "/v2/retention/holds/malformed/release", "/v2/storage/reservations",
            "/v2/storage/reservations/malformed/release", "/v2/cleanup/previews",
            "/v2/cleanup/previews/malformed/execute", "/v2/builds", "/v2/builds/malformed:confirm",
            "/v2/builds/malformed:scan-continue", "/v2/builds/malformed:cancel", "/v2/comparisons",
            "/v2/comparisons/malformed:retry-resource", "/v2/toolchains/plans:resolve",
            "/v2/toolchains/installations", "/v2/toolchains/installations/malformed:cancel",
            "/v2/toolchains/removals:preview", "/v2/toolchains/removals:execute",
            "/v2/authentication/self-revoke",
        )
        for (authorization in listOf(null, "Bearer malformed", "Bearer rdb1.00000000-0000-4000-8000-000000000099.${"A".repeat(43)}")) {
            for (path in gets) {
                val response = client.get(path) { if (authorization != null) header(HttpHeaders.Authorization, authorization) }
                assertEquals(HttpStatusCode.Unauthorized, response.status, path)
                responses += response.bodyAsText()
            }
            for (path in posts) {
                val response = client.post(path) {
                    if (authorization != null) header(HttpHeaders.Authorization, authorization)
                    contentType(ContentType.Application.Json)
                    setBody("{invalid-json")
                }
                assertEquals(HttpStatusCode.Unauthorized, response.status, path)
                responses += response.bodyAsText()
            }
        }
        assertEquals(1, responses.size, "Unknown, absent and malformed bearer failures must not enumerate resources")
        assertEquals("UNAUTHORIZED", Json.parseToJsonElement(responses.single()).jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(0, count("operations"))
        assertEquals(0, count("jobs"))
    }

    @Test
    fun malformedPairingCreatesConsumeRateBudgetWithoutCreatingInvitationsOrRequests() = testApplication {
        application { runnerModule(config()) }
        repeat(10) {
            val response = client.post("/pairing/v1/requests") {
                contentType(ContentType.Application.Json)
                setBody("{\"schemaVersion\":1,\"schemaVersion\":1}")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        val blocked = client.post("/pairing/v1/requests") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        val cancel = client.post("/pairing/v1/requests/malformed/cancel") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.TooManyRequests, cancel.status)
        assertEquals(0, count("pairing_requests"))
        assertEquals(0, count("pairing_invitations"))
    }

    @Test
    fun revokedBearerCannotReplayCachedOperationAndOtherPrincipalsCannotReadIt() = testApplication {
        SQLiteJobStore(directory)
        val security = RunnerSecurityStore(directory)
        val first = approve(security)
        val second = approve(security)
        val operationId = UUID.randomUUID().toString()
        val idempotencyKey = UUID.randomUUID().toString()
        DriverManager.getConnection(dbUrl()).use { connection -> connection.createStatement().use { statement ->
            statement.executeUpdate("""
                INSERT INTO operations(operation_id,principal_id,operation_kind,idempotency_key,request_sha256,
                    contract_id,contract_version,state,result_type,result_id,result_json,created_at,updated_at)
                VALUES('$operationId','${first.principal}','retention-hold-create','$idempotencyKey','${"a".repeat(64)}',
                    'storage-retention',1,'COMPLETED','RETENTION_HOLD','sensitive-resource','{}','2026-09-08T00:00:00Z','2026-09-08T00:00:00Z')
            """.trimIndent())
        } }
        application { runnerModule(config()) }
        assertEquals(HttpStatusCode.OK, client.get("/v1/health") { bearerAuth(first.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/operations/$operationId") { bearerAuth(second.token) }.status)
        security.revoke(first.principal)
        val replay = client.post("/v2/retention/holds") {
            bearerAuth(first.token)
            header("Idempotency-Key", idempotencyKey)
            header("X-ReproDroid-Contract", "storage-retention@1")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertFalse(replay.bodyAsText().contains("sensitive-resource"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/operations/$operationId") { bearerAuth(first.token) }.status)
        assertEquals(1, count("operations"))
        assertEquals(0, count("retention_holds"))
    }

    private fun approve(store: RunnerSecurityStore): Approved {
        val invitation = store.openInvitation("https://127.0.0.1:8443", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        val tokenId = UUID.randomUUID().toString()
        val token = "rdb1.$tokenId.${"A".repeat(43)}"
        val pending = store.createRequest(PairingCreateRequest(
            schemaVersion = 1,
            runnerId = invitation.runnerId, invitationId = invitation.invitationId,
            invitationSecret = invitation.invitationSecret, deviceDisplayName = "test device",
            tokenId = tokenId, tokenSha256 = hexSha256(token), continuationId = UUID.randomUUID().toString(),
            continuationSha256 = hexSha256("A".repeat(43)),
        ), "127.0.0.1")
        return Approved(requireNotNull(store.decide(pending.requestId, true).principalId), token)
    }

    private fun config() = RunnerConfig("127.0.0.1", 8443, directory, apiV2Enabled = true,
        transportMode = TransportMode.PAIRED_HTTPS, advertisedEndpoint = URI("https://127.0.0.1:8443"))
    private fun dbUrl() = "jdbc:sqlite:${directory.resolve("reprodroid-runner.sqlite3")}"
    private fun count(table: String): Int = DriverManager.getConnection(dbUrl()).use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) } }
    }
    private class Approved(val principal: String, val token: String)
}
