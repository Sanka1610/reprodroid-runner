package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SandboxPersistenceTest {
    @TempDir lateinit var directory: Path

    @Test fun `config defaults to HOST and invalid explicit values never default`() {
        assertEquals(BuildSandboxMode.HOST, RunnerConfig.fromEnvironment(emptyMap()).buildSandbox)
        assertEquals(BuildSandboxMode.DOCKER, RunnerConfig.fromEnvironment(mapOf("REPRODROID_BUILD_SANDBOX" to "DOCKER")).buildSandbox)
        assertFalse(RunnerConfig.fromEnvironment(mapOf("REPRODROID_BUILD_SANDBOX" to "DOCKER")).realBuildEnabled)
        listOf("", " ", "docker", "HOST ", "OTHER").forEach { value ->
            assertTrue(assertThrows(IllegalArgumentException::class.java) {
                RunnerConfig.fromEnvironment(mapOf("REPRODROID_BUILD_SANDBOX" to value))
            }.message!!.startsWith("SANDBOX_CONFIG_INVALID"))
        }
    }

    @Test fun `creation persists complete snapshot before effective build and restoration preserves selection`() {
        val store = SQLiteJobStore(directory)
        val hostId = store.createJob(request()).jobId
        val dockerId = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
        val simulated = store.createJob(request().copy(executionMode = ExecutionMode.SIMULATED, simulationOutcome = SimulationOutcome.SUCCESS), BuildSandboxMode.DOCKER).jobId
        val restored = SQLiteJobStore(directory)
        assertEquals(JobSandbox(BuildSandboxMode.HOST, SandboxOrigin.NEW_JOB), restored.getJob(hostId)?.sandbox)
        assertEquals(BuildSandboxMode.DOCKER, restored.getJob(dockerId)?.sandbox?.mode)
        assertEquals(null, restored.getJob(dockerId)?.effectiveBuild)
        assertEquals(null, restored.getJob(simulated)?.sandbox)
        assertEquals(DockerSandboxProfile(), restored.getStoredJob(dockerId)?.sandbox?.validatedProfile())
        val raw = Json { explicitNulls = false }.encodeToString(restored.getJob(hostId))
        assertTrue(raw.contains("\"sandbox\":{\"mode\":\"HOST\",\"origin\":\"NEW_JOB\"}"))
        assertFalse(raw.contains("profileId"))
        assertFalse(raw.contains("snapshot"))
    }

    @Test fun `new generic snapshots use v3 while canonical v1 and v2 snapshots remain readable`() {
        val current = SandboxSnapshot.newGenericJob()
        assertEquals(DetachedGitGenericDockerSandboxProfile.ID, current.jobSandbox.profileId)
        assertEquals(DetachedGitGenericDockerSandboxProfile(), current.validatedGenericProfile())

        val v2 = GenericDockerSandboxProfile()
        val v2Json = Json { encodeDefaults = true; ignoreUnknownKeys = false }.encodeToString(v2)
        val v2Sha256 = MessageDigest.getInstance("SHA-256")
            .digest(v2Json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val restoredV2 = SandboxSnapshot(
            JobSandbox(BuildSandboxMode.DOCKER, SandboxOrigin.NEW_JOB, v2.profileId, SandboxCleanupStatus.COMPLETE),
            v2Json,
            v2Sha256,
            SandboxManifestFormat.REQUIRED_V4,
        )
        assertEquals(v2, restoredV2.validatedGenericProfile())

        val legacy = LegacyGenericDockerSandboxProfile()
        val canonicalJson = """{"profileId":"docker-generic-v1","image":"ubuntu@sha256:1e0a86e57d247923571b75e0aaf48a1449cf8c543d51fb3e07a4a7d7bfa79316","platform":"linux/amd64","dockerExecutable":"/usr/bin/docker","endpoint":"unix:///var/run/docker.sock","uid":1000,"gid":1000,"cpuCount":4,"cpuset":"0-3","memoryBytes":8589934592,"memorySwapBytes":8589934592,"pids":1024,"tmpfsBytes":1073741824,"networkMode":"BRIDGE","readOnlyRoot":true,"capDropAll":true,"noNewPrivileges":true,"seccomp":"DEFAULT","sdkReadOnly":true,"jdkReadOnly":true,"gradleReadOnly":true,"dockerSocketMounted":false,"jobDiskQuotaEnforced":false,"minimumFreeDiskBytes":17179869184,"home":"/home/ubuntu","source":"/work/source","gradleHome":"/work/gradle-home","jdk":"/opt/jdk","sdk":"/opt/android-sdk","gradle":"/opt/gradle","maxGradleWorkers":2,"detailedLogBytes":67108864}"""
        assertEquals(canonicalJson, Json { encodeDefaults = true; ignoreUnknownKeys = false }.encodeToString(legacy))
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals("0162d71930ce7661b5226f5d806477ff970671df4c4977efa5f73ec39583fd62", sha256)
        val restored = SandboxSnapshot(
            JobSandbox(BuildSandboxMode.DOCKER, SandboxOrigin.NEW_JOB, legacy.profileId, SandboxCleanupStatus.COMPLETE),
            canonicalJson,
            sha256,
            SandboxManifestFormat.REQUIRED_V4,
        )
        assertEquals(legacy, restored.validatedGenericProfile())

        val wrongIdentity = restored.copy(jobSandbox = restored.jobSandbox.copy(profileId = DetachedGitGenericDockerSandboxProfile.ID))
        assertEquals("SANDBOX_SNAPSHOT_INVALID", assertThrows(TrustedBuildFailure::class.java) {
            wrongIdentity.validatedGenericProfile()
        }.code)
    }

    @Test fun `missing and tampered snapshot fields fail closed`() {
        val store = SQLiteJobStore(directory)
        listOf(
            "sandbox_mode = NULL", "sandbox_mode = 'UNKNOWN'", "sandbox_origin = 'LEGACY_HOST'",
            "sandbox_profile_id = 'other'", "sandbox_cleanup_status = NULL", "sandbox_snapshot = '{}'",
            "sandbox_snapshot_sha256 = 'bad'", "manifest_format = 'LEGACY_ALLOWED'",
        ).forEach { mutation ->
            val id = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
            sql("UPDATE jobs SET $mutation WHERE job_id = '$id'")
            assertEquals("SANDBOX_SNAPSHOT_INVALID", assertThrows(TrustedBuildFailure::class.java) { store.getJob(id) }.code)
        }
    }

    @Test fun `failed snapshot insert never queues work and leaves no partial job`() = runBlocking {
        val store = SQLiteJobStore(directory)
        val resolverCalls = AtomicInteger()
        val resolver = object : SourceResolver {
            override suspend fun resolve(recipe: BuildRecipe, revision: RequestedRevision, jobId: String): String {
                resolverCalls.incrementAndGet()
                return "a".repeat(40)
            }
        }
        JobCoordinator(store, Dispatchers.IO, 1, true, directory, sourceResolver = resolver,
            buildSandbox = BuildSandboxMode.DOCKER, recoveryEngine = FakeRecoveryEngine()).use { coordinator ->
            sql("CREATE TRIGGER reject_snapshot BEFORE INSERT ON jobs WHEN NEW.execution_mode='REAL_TRUSTED' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertThrows(java.sql.SQLException::class.java) { coordinator.create(request()) }
            assertEquals(0, jobCount())
            assertTrue(store.sandboxResources().isEmpty())
            // A following simulated job is a queue barrier. No failed REAL job may reach its resolver.
            val simulation = coordinator.create(request().copy(executionMode = ExecutionMode.SIMULATED, simulationOutcome = SimulationOutcome.SUCCESS))
            kotlinx.coroutines.withTimeout(5_000) {
                while (!coordinator.get(simulation.jobId).state.isTerminal) delay(10)
            }
            assertEquals(0, resolverCalls.get())
            assertEquals(1, jobCount())
        }
    }

    @Test fun `actual schema seven migration preserves jobs and only unfinished build upgrades manifest policy`() {
        val store = SQLiteJobStore(directory)
        val id = store.createJob(request()).jobId
        val simulated = store.createJob(request().copy(executionMode = ExecutionMode.SIMULATED, simulationOutcome = SimulationOutcome.SUCCESS)).jobId
        val oldLog = store.getLogs(id, 0, 100)
        listOf("sandbox_mode", "sandbox_origin", "sandbox_profile_id", "sandbox_snapshot", "sandbox_snapshot_sha256", "sandbox_cleanup_status", "manifest_format").forEach {
            sql("ALTER TABLE jobs DROP COLUMN $it")
        }
        sql("PRAGMA user_version = 7")
        val migrated = SQLiteJobStore(directory)
        assertEquals(JobSandbox(BuildSandboxMode.HOST, SandboxOrigin.LEGACY_HOST), migrated.getJob(id)?.sandbox)
        assertNull(migrated.getJob(simulated)?.sandbox)
        assertEquals(oldLog, migrated.getLogs(id, 0, 100))
        assertEquals(SandboxManifestFormat.LEGACY_ALLOWED, migrated.getStoredJob(id)?.sandbox?.manifestFormat)
        migrated.requireCurrentManifest(id)
        migrated.requireCurrentManifest(id)
        assertEquals(SandboxOrigin.LEGACY_HOST, migrated.getJob(id)?.sandbox?.origin)
        assertEquals(SandboxManifestFormat.REQUIRED_V4, SQLiteJobStore(directory).getStoredJob(id)?.sandbox?.manifestFormat)
    }

    @Test fun `intent and cleanup are atomic durable and complete is not build success`() = runBlocking {
        val store = SQLiteJobStore(directory)
        val owner = store.sandboxOwnerId()
        assertEquals(owner, SQLiteJobStore(directory).sandboxOwnerId())
        val id = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
        val engine = FakeRecoveryEngine()
        val lifecycle = SandboxLifecycle(store, engine, owner)
        store.updateState(id, JobState.VERIFYING_WRAPPER, 40)
        val first = lifecycle.intent(id, SandboxResourceRole.PREFLIGHT, engine.engineId)
        assertEquals(SandboxCleanupStatus.PENDING, store.getJob(id)?.sandbox?.cleanupStatus)
        engine.containers += SandboxContainerIdentity("a".repeat(64), first.expectedName, first.labels)
        // Deliberately omit ID persistence, modeling a crash after Docker create returns.
        assertTrue(lifecycle.recover())
        assertEquals(listOf("a".repeat(64)), engine.removed)
        assertEquals(SandboxCleanupStatus.COMPLETE, store.getJob(id)?.sandbox?.cleanupStatus)
        assertEquals(JobState.VERIFYING_WRAPPER, store.getJob(id)?.state)
        assertEquals("a".repeat(64), SQLiteJobStore(directory).sandboxResources().single().containerId)
        assertTrue(lifecycle.recover())
        lifecycle.intent(id, SandboxResourceRole.BUILD, engine.engineId)
        assertEquals(SandboxCleanupStatus.PENDING, store.getJob(id)?.sandbox?.cleanupStatus)
    }

    @Test fun `foreign resources changed engine and unknown intent never authorize deletion`() = runBlocking {
        val store = SQLiteJobStore(directory)
        val owner = store.sandboxOwnerId()
        val id = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
        val engine = FakeRecoveryEngine()
        val lifecycle = SandboxLifecycle(store, engine, owner)
        store.updateState(id, JobState.VERIFYING_WRAPPER, 40)
        val intent = lifecycle.intent(id, SandboxResourceRole.BUILD, engine.engineId)
        val foreign = SandboxContainerIdentity("b".repeat(64), intent.expectedName, intent.labels + ("com.reprodroid.job" to UUID.randomUUID().toString()))
        engine.containers += foreign
        assertFalse(lifecycle.recover())
        assertTrue(lifecycle.cleanupPending)
        assertTrue(engine.removed.isEmpty())
        engine.containers.clear()
        engine.engineId = "another-engine"
        assertFalse(lifecycle.recover())
        assertEquals(SandboxCleanupStatus.PENDING, store.getJob(id)?.sandbox?.cleanupStatus)
        engine.engineId = "engine"
        assertTrue(lifecycle.recover())
    }

    @Test fun `exclusive state lease prevents simultaneous controllers`() {
        SandboxStateLease(directory).use {
            assertThrows(Exception::class.java) { SandboxStateLease(directory) }
        }
        SandboxStateLease(directory).close()
    }

    @Test fun `failed coordinator initialization releases its state lease without launching work`() {
        val store = SQLiteJobStore(directory)
        val owner = store.sandboxOwnerId()
        sql("UPDATE sandbox_owner SET owner_id = 'invalid-owner' WHERE singleton = 1")
        assertThrows(TrustedBuildFailure::class.java) {
            JobCoordinator(store, Dispatchers.IO, 1, true, directory, recoveryEngine = FakeRecoveryEngine())
        }
        SandboxStateLease(directory).close()
        sql("UPDATE sandbox_owner SET owner_id = '$owner' WHERE singleton = 1")
        // Also cover a failure after the lifecycle was created, during restart reconciliation.
        val id = store.createJob(request()).jobId
        store.updateState(id, JobState.BUILDING, 50)
        sql("CREATE TRIGGER reject_restart BEFORE UPDATE ON jobs BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertThrows(java.sql.SQLException::class.java) {
            JobCoordinator(store, Dispatchers.IO, 1, true, directory, recoveryEngine = FakeRecoveryEngine())
        }
        SandboxStateLease(directory).close()
        sql("DROP TRIGGER reject_restart")
        JobCoordinator(store, Dispatchers.IO, 1, true, directory, recoveryEngine = FakeRecoveryEngine()).use {
            assertEquals(JobState.INTERRUPTED, it.get(id).state)
        }
    }

    @Test fun `failed recovery blocks real create and retry but allows simulated work`() = runBlocking {
        val store = SQLiteJobStore(directory)
        val owner = store.sandboxOwnerId()
        val id = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
        store.updateState(id, JobState.VERIFYING_WRAPPER, 40)
        SandboxLifecycle(store, FakeRecoveryEngine(), owner).intent(id, SandboxResourceRole.BUILD, "original-engine")
        JobCoordinator(store, Dispatchers.IO, 1, true, directory, recoveryEngine = FakeRecoveryEngine()).use { coordinator ->
            assertEquals("SANDBOX_CLEANUP_PENDING", assertThrows(ApiException::class.java) { coordinator.create(request()) }.code)
            assertEquals(io.ktor.http.HttpStatusCode.ServiceUnavailable, assertThrows(ApiException::class.java) { coordinator.retry(id) }.status)
            val simulation = coordinator.create(request().copy(executionMode = ExecutionMode.SIMULATED, simulationOutcome = SimulationOutcome.SUCCESS))
            kotlinx.coroutines.withTimeout(5_000) {
                while (!coordinator.get(simulation.jobId).state.isTerminal) delay(10)
            }
            assertEquals(JobState.SUCCEEDED, coordinator.get(simulation.jobId).state)
            assertEquals(SandboxCleanupStatus.PENDING, coordinator.get(id).sandbox?.cleanupStatus)
        }
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM jobs").use { it.next(); assertEquals(2, it.getInt(1)) }
            }
        }
    }

    @Test fun `failed intent transaction leaves NOT_CREATED and prevents subsequent execution`() {
        val store = SQLiteJobStore(directory)
        val owner = store.sandboxOwnerId()
        val id = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
        store.updateState(id, JobState.VERIFYING_WRAPPER, 40)
        sql("CREATE TRIGGER reject_intent BEFORE INSERT ON sandbox_resources BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        val lifecycle = SandboxLifecycle(store, FakeRecoveryEngine(), owner)
        assertEquals("SANDBOX_AUDIT_PERSISTENCE_FAILED", assertThrows(TrustedBuildFailure::class.java) {
            lifecycle.intent(id, SandboxResourceRole.BUILD, "engine")
        }.code)
        assertTrue(lifecycle.cleanupPending)
        assertTrue(store.sandboxResources().isEmpty())
        assertEquals(SandboxCleanupStatus.NOT_CREATED, store.getJob(id)?.sandbox?.cleanupStatus)
    }

    @Test fun `manifest requirement write failure is typed and never starts a build`() {
        val store = SQLiteJobStore(directory)
        val id = store.createJob(request()).jobId
        sql("CREATE TRIGGER reject_manifest BEFORE UPDATE OF manifest_format ON jobs BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertEquals("SANDBOX_AUDIT_PERSISTENCE_FAILED", assertThrows(TrustedBuildFailure::class.java) { store.requireCurrentManifest(id) }.code)
        assertEquals(JobState.CREATED, store.getJob(id)?.state)
    }

    @Test fun `cleanup database failure remains pending and repeated recovery reconciles absence`() = runBlocking {
        val store = SQLiteJobStore(directory)
        val id = store.createJob(request(), BuildSandboxMode.DOCKER).jobId
        store.updateState(id, JobState.VERIFYING_WRAPPER, 40)
        val engine = FakeRecoveryEngine()
        val lifecycle = SandboxLifecycle(store, engine, store.sandboxOwnerId())
        val intent = lifecycle.intent(id, SandboxResourceRole.BUILD, engine.engineId)
        engine.containers += SandboxContainerIdentity("c".repeat(64), intent.expectedName, intent.labels)
        sql("CREATE TRIGGER reject_cleanup BEFORE UPDATE OF removed ON sandbox_resources BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertFalse(lifecycle.recover())
        assertEquals(SandboxCleanupStatus.PENDING, store.getJob(id)?.sandbox?.cleanupStatus)
        assertTrue(engine.containers.isEmpty())
        sql("DROP TRIGGER reject_cleanup")
        assertTrue(lifecycle.recover())
        assertEquals(SandboxCleanupStatus.COMPLETE, store.getJob(id)?.sandbox?.cleanupStatus)
        assertEquals(listOf("c".repeat(64)), engine.removed)
    }

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun jobCount(): Int = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM jobs").use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun request() = CreateJobRequest(ExecutionMode.REAL_TRUSTED, "https://github.com/MorpheApp/MicroG-RE.git", RequestedRevision(RevisionType.TAG, "6.1.4"))
}

internal class FakeRecoveryEngine : SandboxRecoveryEngine {
    var engineId = "engine"
    val containers = mutableListOf<SandboxContainerIdentity>()
    val removed = mutableListOf<String>()
    override suspend fun identity(endpoint: String) = engineId
    override suspend fun ownedContainers(endpoint: String, ownerId: String) = containers.toList()
    override suspend fun remove(endpoint: String, containerId: String) {
        removed += containerId
        containers.removeAll { it.id == containerId }
    }
}
