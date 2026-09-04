package com.sanka1610.reprodroid.runner

import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class StorageRetentionApiTest {
    @TempDir
    lateinit var stateDirectory: Path

    @BeforeEach
    fun setup() {
        Files.createDirectories(stateDirectory)
    }

    @AfterEach
    fun cleanup() = Unit

    @Test
    fun `v2 capabilities are stable and v1 execution mutations fail closed`() = testApplication {
        application { runnerModule(testConfig()) }
        val client = jsonClient()

        val first = client.get("/v2/capabilities").body<V2CapabilitiesResponse>()
        val second = client.get("/v2/capabilities").body<V2CapabilitiesResponse>()
        assertEquals(first.runnerId, second.runnerId)
        assertEquals(
            listOf(V2Capability("foundation", 1), V2Capability("storage-retention", 1)),
            first.capabilities,
        )
        val wire = client.get("/v2/capabilities").bodyAsText()
        assertTrue(wire.contains("\"apiVersion\":\"v2\""))
        assertTrue(wire.contains("\"foundationContractVersion\":1"))
        assertTrue(wire.contains("\"runnerVersion\":\"0.1.0-alpha01\""))
        assertTrue(wire.contains("\"capabilities\":["))

        val storageWire = client.get("/v2/storage/summary") {
            header("X-ReproDroid-Contract", "storage-retention@1")
        }.bodyAsText()
        assertTrue(storageWire.contains("\"schemaVersion\":1"))

        val legacy = client.post("/v1/jobs") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"executionMode":"SIMULATED","repositoryUrl":"https://github.com/example/app.git",${
                    "\"revision\":{\"type\":\"BRANCH\",\"value\":\"main\"},\"simulationOutcome\":\"SUCCESS\""
                }}""",
            )
        }
        assertEquals(HttpStatusCode.UpgradeRequired, legacy.status)
        assertEquals("API_UPGRADE_REQUIRED", legacy.body<ApiErrorResponse>().code)
    }

    @Test
    fun `strict v2 JSON rejects duplicate and unknown fields before operation persistence`() = testApplication {
        val jobId = seedTerminalJob()
        application { runnerModule(testConfig()) }
        val client = jsonClient()
        val key = UUID.randomUUID().toString()

        val duplicate = client.post("/v2/retention/holds") {
            mutationHeaders(key)
            setBody(
                """{"resource":{"kind":"JOB","id":"$jobId"},"reason":"CURRENT_COMPARISON",${
                    "\"reason\":\"CURRENT_RELEASE\",\"clientReference\":{\"type\":\"COMPARISON\",\"id\":\"${UUID.randomUUID()}\"}"
                }}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)
        assertEquals("INVALID_JSON", duplicate.body<ApiErrorResponse>().code)

        val unknown = client.post("/v2/retention/holds") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody(
                """{"resource":{"kind":"JOB","id":"$jobId"},"reason":"CURRENT_COMPARISON",${
                    "\"clientReference\":{\"type\":\"COMPARISON\",\"id\":\"${UUID.randomUUID()}\"},\"extra\":true"
                }}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM operations"))
    }

    @Test
    fun `strict v2 JSON enforces body and nesting limits before operation persistence`() = testApplication {
        application { runnerModule(testConfig()) }
        val client = jsonClient()

        val oversized = client.post("/v2/retention/holds") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody("{\"padding\":\"${"x".repeat(256 * 1024)}\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("REQUEST_TOO_LARGE", oversized.body<ApiErrorResponse>().code)

        val deeplyNested = client.post("/v2/retention/holds") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody("{\"value\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}")
        }
        assertEquals(HttpStatusCode.BadRequest, deeplyNested.status)
        assertEquals("INVALID_JSON", deeplyNested.body<ApiErrorResponse>().code)
        assertEquals(0, sqlLong("SELECT COUNT(*) FROM operations"))
    }

    @Test
    fun `hold idempotency survives restart and conflicting request is rejected`() = testApplication {
        val jobId = seedTerminalJob()
        application { runnerModule(testConfig()) }
        val client = jsonClient()
        val key = UUID.randomUUID().toString()
        val referenceId = UUID.randomUUID().toString()
        val body = holdBody(jobId, "CURRENT_COMPARISON", referenceId)

        val created = client.post("/v2/retention/holds") { mutationHeaders(key); setBody(body) }
        assertEquals(HttpStatusCode.Accepted, created.status)
        val first = created.body<V2OperationResponse>()
        assertEquals(V2OperationState.COMPLETED, first.state)
        val holdId = requireNotNull(first.result).resourceId

        val replay = client.post("/v2/retention/holds") { mutationHeaders(key); setBody(body) }
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(first.operationId, replay.body<V2OperationResponse>().operationId)
        assertEquals(1, sqlLong("SELECT COUNT(*) FROM retention_holds"))

        val conflict = client.post("/v2/retention/holds") {
            mutationHeaders(key)
            setBody(holdBody(jobId, "CURRENT_RELEASE", referenceId))
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.body<ApiErrorResponse>().code)

        val releaseKey = UUID.randomUUID().toString()
        val releaseBody = """{"reason":"REFERENCE_RELEASED"}"""
        val released = client.post("/v2/retention/holds/$holdId/release") {
            mutationHeaders(releaseKey)
            setBody(releaseBody)
        }.body<V2OperationResponse>()
        val releaseReplay = client.post("/v2/retention/holds/$holdId/release") {
            mutationHeaders(releaseKey)
            setBody(releaseBody)
        }
        assertEquals(HttpStatusCode.OK, releaseReplay.status)
        assertEquals(released.operationId, releaseReplay.body<V2OperationResponse>().operationId)
    }

    @Test
    fun `reservation accepts exact budget and rejects aggregate plus one`() = testApplication {
        val firstJob = seedTerminalJob()
        val secondJob = seedTerminalJob()
        val measured = StorageRetentionStore(stateDirectory).storageSummary().areas
            .single { it.area == StorageArea.RUNNER_JOB }.usedBytes.toLong()
        val requested = 1024L * 1024L
        sqlUpdate(
            "UPDATE storage_settings SET budget_bytes = ${measured + requested} WHERE area = 'RUNNER_JOB'",
        )
        application { runnerModule(testConfig()) }
        val client = jsonClient()

        val exact = client.post("/v2/storage/reservations") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody(reservationBody(firstJob, requested))
        }
        assertEquals(HttpStatusCode.Accepted, exact.status)
        assertEquals(V2OperationState.COMPLETED, exact.body<V2OperationResponse>().state)

        val plusOne = client.post("/v2/storage/reservations") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody(reservationBody(secondJob, 1))
        }
        assertEquals(HttpStatusCode.Conflict, plusOne.status)
        assertEquals("STORAGE_BUDGET_EXCEEDED", plusOne.body<ApiErrorResponse>().code)
        assertEquals(requested, sqlLong("SELECT SUM(requested_bytes) FROM storage_reservations WHERE state = 'ACTIVE'"))
    }

    @Test
    fun `cleanup preview preserves held workspace then deletes it after explicit release`() = testApplication {
        val jobId = seedTerminalJob(old = true)
        val workspace = stateDirectory.resolve("workspaces").resolve(jobId)
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("payload.bin"), "retained-data")
        application { runnerModule(testConfig()) }
        val client = jsonClient()

        val holdOperation = client.post("/v2/retention/holds") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody(holdBody(jobId, "CURRENT_COMPARISON", UUID.randomUUID().toString()))
        }.body<V2OperationResponse>()
        val holdId = requireNotNull(holdOperation.result).resourceId

        val firstPreviewId = createWorkspacePreview(client).result!!.resourceId
        val firstPreview = client.get("/v2/cleanup/previews/$firstPreviewId").body<CleanupPreviewResponse>()
        assertEquals(listOf("RETENTION_HOLD"), firstPreview.items.single().protectionReasons)
        val protectedRunId = executePreview(client, firstPreview).result!!.resourceId
        val protectedRun = client.get("/v2/cleanup/runs/$protectedRunId").body<CleanupRunResponse>()
        assertEquals("PARTIAL", protectedRun.state)
        assertEquals("SKIPPED_PROTECTED", protectedRun.items.single().result)
        assertTrue(Files.exists(workspace))

        client.post("/v2/retention/holds/$holdId/release") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody("""{"reason":"REFERENCE_RELEASED"}""")
        }
        val secondPreviewId = createWorkspacePreview(client).result!!.resourceId
        val secondPreview = client.get("/v2/cleanup/previews/$secondPreviewId").body<CleanupPreviewResponse>()
        assertTrue(secondPreview.items.single().protectionReasons.isEmpty())
        val completedRunId = executePreview(client, secondPreview).result!!.resourceId
        val completed = client.get("/v2/cleanup/runs/$completedRunId").body<CleanupRunResponse>()
        assertEquals("COMPLETE", completed.state)
        assertEquals("DELETED", completed.items.single().result)
        assertFalse(Files.exists(workspace))
        assertEquals("DELETED", sqlString("SELECT state FROM resource_availability WHERE resource_id = '$jobId'"))
    }

    @Test
    fun `cleanup revalidation skips workspace changed after preview`() = testApplication {
        val jobId = seedTerminalJob(old = true)
        val workspace = stateDirectory.resolve("workspaces").resolve(jobId)
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("first.bin"), "first")
        application { runnerModule(testConfig()) }
        val client = jsonClient()

        val previewId = createWorkspacePreview(client).result!!.resourceId
        val preview = client.get("/v2/cleanup/previews/$previewId").body<CleanupPreviewResponse>()
        Files.writeString(workspace.resolve("second.bin"), "changed-after-preview")
        val runId = executePreview(client, preview).result!!.resourceId
        val run = client.get("/v2/cleanup/runs/$runId").body<CleanupRunResponse>()

        assertEquals("PARTIAL", run.state)
        assertEquals("SKIPPED_PROTECTED", run.items.single().result)
        assertEquals("RESOURCE_CHANGED", requireNotNull(run.items.single().reason).code)
        assertTrue(Files.exists(workspace.resolve("second.bin")))
    }

    @Test
    fun `cleanup never follows a workspace symlink outside the state directory`() = testApplication {
        val jobId = seedTerminalJob(old = true)
        val outside = Files.createTempDirectory("reprodroid-cleanup-outside-")
        val outsideFile = Files.writeString(outside.resolve("must-remain.bin"), "outside")
        val workspace = stateDirectory.resolve("workspaces").resolve(jobId)
        Files.createDirectories(workspace.parent)
        Files.createSymbolicLink(workspace, outside)
        try {
            application { runnerModule(testConfig()) }
            val client = jsonClient()

            val previewId = createWorkspacePreview(client).result!!.resourceId
            val preview = client.get("/v2/cleanup/previews/$previewId").body<CleanupPreviewResponse>()
            assertEquals(listOf("RESOURCE_CHANGED"), preview.items.single().protectionReasons)
            val runId = executePreview(client, preview).result!!.resourceId
            val run = client.get("/v2/cleanup/runs/$runId").body<CleanupRunResponse>()

            assertEquals("PARTIAL", run.state)
            assertEquals("SKIPPED_PROTECTED", run.items.single().result)
            assertTrue(Files.isSymbolicLink(workspace))
            assertTrue(Files.exists(outsideFile))
        } finally {
            Files.deleteIfExists(workspace)
            Files.deleteIfExists(outsideFile)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `restart marks interrupted cleanup with an unpersisted deletion for reconciliation`() = testApplication {
        val jobId = seedTerminalJob(old = true)
        val workspace = stateDirectory.resolve("workspaces").resolve(jobId)
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("payload.bin"), "delete-before-crash")
        application { runnerModule(testConfig()) }
        val client = jsonClient()

        val previewId = createWorkspacePreview(client).result!!.resourceId
        val preview = client.get("/v2/cleanup/previews/$previewId").body<CleanupPreviewResponse>()
        val cleanupRunId = sqlString("SELECT cleanup_run_id FROM cleanup_runs WHERE preview_id = '$previewId'")
        val operationId = UUID.randomUUID().toString()
        val timestamp = Instant.now().toString()
        sqlUpdate(
            """
            INSERT INTO operations (
                operation_id, principal_id, operation_kind, idempotency_key, request_sha256,
                contract_id, contract_version, state, created_at, updated_at
            ) VALUES (
                '$operationId', 'local-development', 'cleanup-execute', '${UUID.randomUUID()}',
                '${"0".repeat(64)}', 'storage-retention', 1, 'APPLYING', '$timestamp', '$timestamp'
            )
            """.trimIndent(),
        )
        sqlUpdate(
            "UPDATE cleanup_runs SET state = 'APPLYING', execute_operation_id = '$operationId', " +
                "started_at = '$timestamp' WHERE cleanup_run_id = '$cleanupRunId'",
        )
        sqlUpdate(
            "UPDATE cleanup_items SET selected = 1 WHERE cleanup_run_id = '$cleanupRunId' " +
                "AND item_id = '${preview.items.single().itemId}'",
        )
        workspace.toFile().deleteRecursively()

        StorageRetentionStore(stateDirectory)

        assertEquals("RECONCILIATION_REQUIRED", sqlString("SELECT state FROM operations WHERE operation_id = '$operationId'"))
        assertEquals("INTERRUPTED_CLEANUP", sqlString("SELECT reason_code FROM operations WHERE operation_id = '$operationId'"))
        assertEquals("RECONCILIATION_REQUIRED", sqlString("SELECT state FROM cleanup_runs WHERE cleanup_run_id = '$cleanupRunId'"))

        val nextJob = seedTerminalJob()
        val blocked = client.post("/v2/storage/reservations") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody(reservationBody(nextJob, 1))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, blocked.status)
        assertEquals("RECONCILIATION_REQUIRED", blocked.body<ApiErrorResponse>().code)
    }

    @Test
    fun `SQLite nine migration creates durable storage tables without changing old job`() {
        val jobId = seedTerminalJob()
        val runnerId = StorageRetentionStore(stateDirectory).runnerId()
        assertEquals(9, sqlLong("PRAGMA user_version"))
        assertEquals(JobState.SUCCEEDED, requireNotNull(SQLiteJobStore(stateDirectory).getJob(jobId)).state)
        assertEquals(runnerId, StorageRetentionStore(stateDirectory).runnerId())
        val tables = setOf(
            "runner_identity", "storage_settings", "operations", "retention_holds",
            "storage_reservations", "resource_availability", "cleanup_runs", "cleanup_items",
        )
        tables.forEach { table ->
            assertEquals(1, sqlLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"))
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.mutationHeaders(key: String) {
        contentType(ContentType.Application.Json)
        header("X-ReproDroid-Contract", "storage-retention@1")
        header("Idempotency-Key", key)
    }

    private suspend fun createWorkspacePreview(client: io.ktor.client.HttpClient): V2OperationResponse =
        client.post("/v2/cleanup/previews") {
            mutationHeaders(UUID.randomUUID().toString())
            setBody(
                """{"area":"RUNNER_JOB","resourceKinds":["JOB_WORKSPACE"],${
                    "\"eligibleBefore\":\"${Instant.now()}\",\"resourceIds\":[]"
                }}""",
            )
        }.body()

    private suspend fun executePreview(
        client: io.ktor.client.HttpClient,
        preview: CleanupPreviewResponse,
    ): V2OperationResponse = client.post("/v2/cleanup/previews/${preview.previewId}/execute") {
        mutationHeaders(UUID.randomUUID().toString())
        setBody("""{"itemIds":["${preview.items.single().itemId}"]}""")
    }.body()

    private fun seedTerminalJob(old: Boolean = false): String {
        val store = SQLiteJobStore(stateDirectory)
        val created = store.createJob(
            CreateJobRequest(
                executionMode = ExecutionMode.SIMULATED,
                repositoryUrl = "https://github.com/example/app.git",
                revision = RequestedRevision(RevisionType.BRANCH, "main"),
                simulationOutcome = SimulationOutcome.SUCCESS,
            ),
        )
        store.updateState(created.jobId, JobState.SUCCEEDED, 100)
        if (old) {
            val timestamp = Instant.now().minus(120, ChronoUnit.DAYS).toString()
            sqlUpdate("UPDATE jobs SET updated_at = '$timestamp' WHERE job_id = '${created.jobId}'")
        }
        return created.jobId
    }

    private fun holdBody(jobId: String, reason: String, referenceId: String): String =
        """{"resource":{"kind":"JOB","id":"$jobId"},"reason":"$reason",${
            "\"clientReference\":{\"type\":\"COMPARISON\",\"id\":\"$referenceId\"}"
        }}"""

    private fun reservationBody(jobId: String, requested: Long): String =
        """{"area":"RUNNER_JOB","purpose":"CLIENT_OPERATION",${
            "\"resource\":{\"kind\":\"JOB\",\"id\":\"$jobId\"},\"requestedBytes\":\"$requested\""
        }}"""

    private fun sqlUpdate(sql: String) {
        DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
    }

    private fun sqlLong(sql: String): Long =
        DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> rows.next(); rows.getLong(1) }
            }
        }

    private fun sqlString(sql: String): String =
        DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) }
            }
        }

    private fun testConfig() = RunnerConfig(
        host = "127.0.0.1",
        port = 8080,
        stateDirectory = stateDirectory,
        simulationStepDelayMillis = 1,
        apiV2Enabled = true,
    )
}
