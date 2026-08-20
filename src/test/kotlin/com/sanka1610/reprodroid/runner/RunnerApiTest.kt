package com.sanka1610.reprodroid.runner

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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
import java.sql.DriverManager

class RunnerApiTest {
    @TempDir
    lateinit var stateDirectory: Path

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

        val logs = client.get("/v1/jobs/${created.jobId}/logs?afterSequence=0&limit=200")
            .body<LogResponse>()
        assertTrue(logs.entries.size >= 6)
        assertEquals(logs.entries.last().sequence, logs.nextAfterSequence)
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
    }

    @Test
    fun `runner refuses a database created by a newer schema`() {
        Class.forName("org.sqlite.JDBC")
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = 2")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            SQLiteJobStore(stateDirectory)
        }
    }

    private fun testConfig() = RunnerConfig(
        host = "127.0.0.1",
        port = 8080,
        stateDirectory = stateDirectory,
        simulationStepDelayMillis = 1,
    )

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
