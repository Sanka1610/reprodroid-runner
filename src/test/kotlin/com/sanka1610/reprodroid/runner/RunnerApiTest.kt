package com.sanka1610.reprodroid.runner

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class RunnerApiTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `source scan raw json is complete and review is digest bound`() = testApplication {
        val store = SQLiteJobStore(stateDirectory)
        val created = store.createJob(
            CreateJobRequest(
                executionMode = ExecutionMode.REAL_TRUSTED,
                repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
                revision = RequestedRevision(RevisionType.COMMIT, SOURCE_SCAN_COMMIT),
            ),
        )
        assertTrue(store.updateState(created.jobId, JobState.SCANNING_SOURCE, 32))
        val checkout = stateDirectory.resolve("scan-fixture").also(Path::createDirectories)
        checkout.resolve("build.gradle.kts").writeText("val process = ProcessBuilder(\"true\")")
        val scan = SourceScanner().scan(created.jobId, checkout, SOURCE_SCAN_COMMIT)
        assertTrue(store.recordSourceScanResult(scan))
        application { runnerModule(testConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false; explicitNulls = false }) }
        }

        val rawJob = client.get("/v1/jobs/${created.jobId}")
        assertEquals(HttpStatusCode.OK, rawJob.status)
        val rawJobText = rawJob.bodyAsBytes().toString(Charsets.UTF_8)
        assertTrue(rawJobText.contains("\"sourceScan\":{"))
        assertTrue(rawJobText.contains("\"status\":\"COMPLETED\""))
        assertTrue(rawJobText.contains("\"resultSha256\":\"${scan.detail.resultSha256}\""))

        val detail = client.get("/v1/jobs/${created.jobId}/source-scan")
        assertEquals(HttpStatusCode.OK, detail.status)
        val rawDetail = detail.bodyAsBytes().toString(Charsets.UTF_8)
        assertTrue(rawDetail.contains("\"schemaVersion\":1"))
        assertTrue(rawDetail.contains("\"scannerVersion\":\"reprodroid-static-v1\""))
        assertFalse(rawDetail.contains("ProcessBuilder"))
        assertFalse(rawDetail.contains(stateDirectory.toString()))

        val rejected = client.post("/v1/jobs/${created.jobId}/source-scan/continue") {
            contentType(ContentType.Application.Json)
            setBody(ContinueSourceScanRequest(scan.detail.resultSha256, riskAcknowledged = false))
        }
        assertEquals(HttpStatusCode.Forbidden, rejected.status)
        assertEquals("SOURCE_SCAN_RISK_ACKNOWLEDGEMENT_REQUIRED", rejected.body<ApiErrorResponse>().code)
    }

    @Test
    fun `simulated success persists progress logs and artifact metadata`() = testApplication {
        application { runnerModule(testConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val createResponse = client.post("/v1/jobs") {
            contentType(ContentType.Application.Json)
            setBody(simulatedRequest(SimulationOutcome.SUCCESS))
        }
        assertEquals(HttpStatusCode.Accepted, createResponse.status)
        val created = createResponse.body<CreateJobResponse>()
        assertEquals(JobState.CREATED, created.state)

        val completed = awaitTerminalJob(client, created.jobId)
        assertEquals(JobState.SUCCEEDED, completed.state)
        assertEquals(100, completed.progressPercent)
        assertEquals(1, completed.artifacts.size)
        assertEquals("0".repeat(64), completed.artifacts.single().sha256)

        val unavailable = client.get(
            "/v1/jobs/${created.jobId}/artifacts/${completed.artifacts.single().artifactId}/content",
        )
        assertEquals(HttpStatusCode.Conflict, unavailable.status)
        assertEquals("ARTIFACT_CONTENT_UNAVAILABLE", unavailable.body<ApiErrorResponse>().code)

        val logs = client.get("/v1/jobs/${created.jobId}/logs?afterSequence=0&limit=200")
            .body<LogResponse>()
        assertTrue(logs.entries.size >= 6)
        assertEquals(logs.entries.last().sequence, logs.nextAfterSequence)
    }

    @Test
    fun `succeeded real artifact content includes immutable transfer metadata`() = testApplication {
        val bytes = "signed-apk-test-content".toByteArray()
        val (jobId, artifact) = seedDownloadableArtifact(bytes)
        application { runnerModule(testConfig()) }

        val response = client.get("/v1/jobs/$jobId/artifacts/${artifact.artifactId}/content")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/vnd.android.package-archive", response.headers[HttpHeaders.ContentType])
        assertEquals(bytes.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertEquals("\"${artifact.sha256}\"", response.headers[HttpHeaders.ETag])
        assertTrue(bytes.contentEquals(response.bodyAsBytes()))
    }

    @Test
    fun `artifact content is rejected after stored bytes no longer match metadata`() = testApplication {
        val (jobId, artifact) = seedDownloadableArtifact("original-apk".toByteArray())
        val storedPath = stateDirectory.resolve("artifacts/$jobId/${artifact.artifactId}.apk")
        Files.writeString(storedPath, "tampered")
        application { runnerModule(testConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val response = client.get("/v1/jobs/$jobId/artifacts/${artifact.artifactId}/content")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("ARTIFACT_CONTENT_INVALID", response.body<ApiErrorResponse>().code)
    }

    @Test
    fun `artifact content is rejected when the stored path becomes a symbolic link`() = testApplication {
        val bytes = "original-apk".toByteArray()
        val (jobId, artifact) = seedDownloadableArtifact(bytes)
        val storedPath = stateDirectory.resolve("artifacts/$jobId/${artifact.artifactId}.apk")
        val externalPath = stateDirectory.resolve("external.apk")
        Files.write(externalPath, bytes)
        Files.delete(storedPath)
        Files.createSymbolicLink(storedPath, externalPath)
        application { runnerModule(testConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val response = client.get("/v1/jobs/$jobId/artifacts/${artifact.artifactId}/content")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("ARTIFACT_CONTENT_INVALID", response.body<ApiErrorResponse>().code)
    }

    @Test
    fun `simulated failure returns a persisted explicit error`() = testApplication {
        application { runnerModule(testConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val created = client.post("/v1/jobs") {
            contentType(ContentType.Application.Json)
            setBody(simulatedRequest(SimulationOutcome.FAILURE))
        }.body<CreateJobResponse>()

        val completed = awaitTerminalJob(client, created.jobId)
        assertEquals(JobState.FAILED, completed.state)
        assertEquals("SIMULATED_BUILD_FAILURE", requireNotNull(completed.error).code)
        assertTrue(completed.artifacts.isEmpty())
    }

    @Test
    fun `real execution and malformed log cursors are rejected`() = testApplication {
        application { runnerModule(testConfig()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val realResponse = client.post("/v1/jobs") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateJobRequest(
                    executionMode = ExecutionMode.REAL_TRUSTED,
                    repositoryUrl = "https://github.com/example/app.git",
                    revision = RequestedRevision(RevisionType.BRANCH, "main"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Forbidden, realResponse.status)

        val malformedCursorResponse = client.get(
            "/v1/jobs/not-used/logs?afterSequence=not-a-number&limit=200",
        )
        assertEquals(HttpStatusCode.BadRequest, malformedCursorResponse.status)

        val credentialResponse = client.post("/v1/jobs") {
            contentType(ContentType.Application.Json)
            setBody(
                simulatedRequest(SimulationOutcome.SUCCESS).copy(
                    repositoryUrl = "https://token@example.com/app.git",
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, credentialResponse.status)
    }

    @Test
    fun `cancel keeps the job terminal even when the executor is running`() = testApplication {
        application {
            runnerModule(
                testConfig().copy(simulationStepDelayMillis = 250),
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val created = client.post("/v1/jobs") {
            contentType(ContentType.Application.Json)
            setBody(simulatedRequest(SimulationOutcome.SUCCESS))
        }.body<CreateJobResponse>()

        withTimeout(5_000) {
            while (client.get("/v1/jobs/${created.jobId}").body<JobResponse>().state == JobState.CREATED) {
                delay(10)
            }
        }
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/v1/jobs/${created.jobId}/cancel").status,
        )
        delay(300)

        val cancelled = client.get("/v1/jobs/${created.jobId}").body<JobResponse>()
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertTrue(cancelled.artifacts.isEmpty())
    }

    @Test
    fun `startup marks persisted in-flight job interrupted`() {
        val store = SQLiteJobStore(stateDirectory)
        val created = store.createJob(simulatedRequest(SimulationOutcome.SUCCESS))
        store.updateState(created.jobId, JobState.BUILDING, 40)

        val restartedStore = SQLiteJobStore(stateDirectory)
        assertEquals(listOf(created.jobId), restartedStore.markRunningJobsInterrupted())
        val interrupted = requireNotNull(restartedStore.getJob(created.jobId))
        assertEquals(JobState.INTERRUPTED, interrupted.state)
        assertEquals("RUNNER_RESTARTED", requireNotNull(interrupted.error).code)
    }

    @Test
    fun `cancelled state cannot be overwritten by late executor transitions`() {
        val store = SQLiteJobStore(stateDirectory)
        val created = store.createJob(simulatedRequest(SimulationOutcome.SUCCESS))
        store.updateState(created.jobId, JobState.BUILDING, 70)

        assertEquals(CancelJobResult.CANCELLED, store.cancelIfActive(created.jobId))
        assertFalse(
            store.transitionIfActive(
                created.jobId,
                JobState.DISCOVERING_ARTIFACTS,
                90,
                "late transition",
            ),
        )
        assertFalse(
            store.completeSimulatedSuccessIfActive(
                created.jobId,
                ArtifactMetadata(
                    artifactId = "late-artifact",
                    fileName = "late.apk",
                    sizeBytes = 1,
                    sha256 = "0".repeat(64),
                    packageName = "example.late",
                    versionName = "1",
                    versionCode = 1,
                ),
            ),
        )

        val cancelled = requireNotNull(store.getJob(created.jobId))
        assertEquals(JobState.CANCELLED, cancelled.state)
        assertTrue(cancelled.artifacts.isEmpty())
    }

    @Test
    fun `runner configuration rejects ambiguous or malformed bind settings`() {
        assertThrows(IllegalArgumentException::class.java) {
            RunnerConfig.fromEnvironment(mapOf("REPRODROID_HOST" to "localhost"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunnerConfig.fromEnvironment(mapOf("REPRODROID_PORT" to "not-a-port"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunnerConfig.fromEnvironment(mapOf("REPRODROID_STATE_DIR" to ""))
        }
        assertTrue(
            RunnerConfig.fromEnvironment(mapOf("REPRODROID_ENABLE_REAL_BUILDS" to "true"))
                .realBuildEnabled,
        )
    }

    @Test
    fun `runner migrates phase one schema and persists real build audit fields`() {
        Class.forName("org.sqlite.JDBC")
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        val jobId = "11111111-1111-1111-1111-111111111111"
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE jobs (
                        job_id TEXT PRIMARY KEY,
                        execution_mode TEXT NOT NULL,
                        repository_url TEXT NOT NULL,
                        revision_type TEXT NOT NULL,
                        revision_value TEXT NOT NULL,
                        simulation_outcome TEXT,
                        resolved_commit_sha TEXT,
                        state TEXT NOT NULL,
                        progress_percent INTEGER NOT NULL,
                        requires_confirmation INTEGER NOT NULL,
                        effective_build_root TEXT,
                        effective_build_tasks TEXT,
                        error_code TEXT,
                        error_message TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO jobs (
                        job_id, execution_mode, repository_url, revision_type, revision_value,
                        state, progress_percent, requires_confirmation, created_at, updated_at
                    ) VALUES (
                        '$jobId', 'REAL_TRUSTED', 'https://github.com/MorpheApp/MicroG-RE.git',
                        'BRANCH', 'main', 'AWAITING_CONFIRMATION', 10, 1,
                        '2026-08-20T00:00:00Z', '2026-08-20T00:00:00Z'
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = 1")
            }
        }

        val store = SQLiteJobStore(stateDirectory)
        assertEquals(JobState.AWAITING_CONFIRMATION, requireNotNull(store.getJob(jobId)).state)
        assertTrue(
            store.recordWrapperVerification(
                jobId,
                WrapperVerification(
                    gradleVersion = "8.14.3",
                    distributionUrl = "https://services.gradle.org/distributions/gradle-8.14.3-bin.zip",
                    distributionSha256 = "a".repeat(64),
                    distributionChecksumSource = "SUPPLIED_BY_RUNNER",
                    wrapperJarGradleVersion = "8.11.1",
                    wrapperJarSha256 = "b".repeat(64),
                ),
            ),
        )
        assertTrue(store.recordBuildManifest(jobId, "manifests/$jobId/reprodroid-build.json", "c".repeat(64)))
        assertTrue(store.recordDependencyLockPreBuild(jobId, "d".repeat(64)))
        assertTrue(store.recordDependencyLockPostBuild(jobId, "d".repeat(64)))

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(12, statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getInt(1)
                })
                statement.executeQuery(
                    """
                    SELECT gradle_version, distribution_sha256, distribution_checksum_source,
                           wrapper_jar_gradle_version, wrapper_jar_sha256, manifest_path, manifest_sha256,
                           effective_dependency_pinning, dependency_lock_pre_sha256,
                           dependency_lock_post_sha256, effective_source_date_epoch,
                           effective_no_build_cache, effective_fixed_locale
                    FROM jobs WHERE job_id = '$jobId'
                    """.trimIndent(),
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("8.14.3", result.getString("gradle_version"))
                    assertEquals("a".repeat(64), result.getString("distribution_sha256"))
                    assertEquals("SUPPLIED_BY_RUNNER", result.getString("distribution_checksum_source"))
                    assertEquals("8.11.1", result.getString("wrapper_jar_gradle_version"))
                    assertEquals("b".repeat(64), result.getString("wrapper_jar_sha256"))
                    assertEquals("manifests/$jobId/reprodroid-build.json", result.getString("manifest_path"))
                    assertEquals("c".repeat(64), result.getString("manifest_sha256"))
                    assertEquals("NONE", result.getString("effective_dependency_pinning"))
                    assertEquals("d".repeat(64), result.getString("dependency_lock_pre_sha256"))
                    assertEquals("d".repeat(64), result.getString("dependency_lock_post_sha256"))
                    assertEquals(null, result.getString("effective_source_date_epoch"))
                    assertEquals(0, result.getInt("effective_no_build_cache"))
                    assertEquals(null, result.getString("effective_fixed_locale"))
                }
                assertTrue(
                    statement.executeQuery("PRAGMA table_info(artifacts)").use { result ->
                        generateSequence { if (result.next()) result.getString("name") else null }
                            .any { it == "content_path" }
                    },
                )
            }
        }
    }

    @Test
    fun `runner refuses a database created by a newer schema`() {
        Class.forName("org.sqlite.JDBC")
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = 11")
            }
        }

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = 13")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            SQLiteJobStore(stateDirectory)
        }
    }

    @Test
    fun `runner migrates phase one C artifact rows to nullable content paths`() {
        Class.forName("org.sqlite.JDBC")
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        SQLiteJobStore(stateDirectory)
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE artifacts DROP COLUMN content_path")
                statement.execute("PRAGMA user_version = 2")
            }
        }

        SQLiteJobStore(stateDirectory)

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(12, statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getInt(1)
                })
                assertTrue(
                    statement.executeQuery("PRAGMA table_info(artifacts)").use { result ->
                        generateSequence { if (result.next()) result.getString("name") else null }
                            .any { it == "content_path" }
                    },
                )
            }
        }
    }

    private companion object {
        const val SOURCE_SCAN_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }

    private fun testConfig() = RunnerConfig(
        host = "127.0.0.1",
        port = 8080,
        stateDirectory = stateDirectory,
        simulationStepDelayMillis = 1,
    )

    private fun seedDownloadableArtifact(bytes: ByteArray): Pair<String, ArtifactMetadata> {
        val store = SQLiteJobStore(stateDirectory)
        val created = store.createJob(
            CreateJobRequest(
                executionMode = ExecutionMode.REAL_TRUSTED,
                repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
                revision = RequestedRevision(RevisionType.BRANCH, "main"),
            ),
        )
        val artifactId = "22222222-2222-2222-2222-222222222222"
        val artifactPath = stateDirectory.resolve("artifacts/${created.jobId}/$artifactId.apk")
        Files.createDirectories(artifactPath.parent)
        Files.write(artifactPath, bytes)
        val artifact = ArtifactMetadata(
            artifactId = artifactId,
            fileName = "verified-debug.apk",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(artifactPath),
            packageName = "",
            versionName = "",
            versionCode = 0,
        )
        assertTrue(
            store.completeRealSuccessIfActive(
                created.jobId,
                listOf(
                    StoredArtifact(
                        metadata = artifact,
                        contentRelativePath = "artifacts/${created.jobId}/$artifactId.apk",
                    ),
                ),
            ),
        )
        return created.jobId to artifact
    }

    private fun simulatedRequest(outcome: SimulationOutcome) = CreateJobRequest(
        executionMode = ExecutionMode.SIMULATED,
        repositoryUrl = "https://github.com/example/app.git",
        revision = RequestedRevision(RevisionType.BRANCH, "main"),
        simulationOutcome = outcome,
    )

    private suspend fun awaitTerminalJob(
        client: io.ktor.client.HttpClient,
        jobId: String,
    ): JobResponse = withTimeout(5_000) {
        while (true) {
            val job = client.get("/v1/jobs/$jobId").body<JobResponse>()
            if (job.state.isTerminal) return@withTimeout job
            delay(10)
        }
        error("unreachable")
    }
}
