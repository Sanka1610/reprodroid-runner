package com.sanka1610.reprodroid.runner

import org.erdtman.jcs.JsonCanonicalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

class GenericBuildTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `resource retry creates a fresh durable pair and is idempotent`() {
        val store = SQLiteJobStore(stateDirectory)
        installManagedToolchainFixture()
        assertTrue(SandboxSnapshot.newGenericJob().validatedGenericProfile() != null)
        val comparisonId = UUID.randomUUID().toString()
        val configuration = configuration()
        val canonical = GenericBuildContract.canonicalConfiguration(configuration)
        val configurationSha256 = GenericBuildContract.hash(JsonCanonicalizer(canonical).encodedUTF8)
        val buildA = createGeneric(store, comparisonId, GenericBuildAttempt.A, configurationSha256, canonical)
        val buildB = createGeneric(store, comparisonId, GenericBuildAttempt.B, configurationSha256, canonical)

        assertTrue(
            store.failIfActive(
                buildA.jobId,
                JobError("SANDBOX_MEMORY_LIMIT_EXCEEDED", "fixture cgroup OOM"),
                "fixture cgroup OOM",
            ),
        )
        markSandboxComplete(buildB.jobId)
        assertTrue(store.updateState(buildB.jobId, JobState.SUCCEEDED, 100))
        val comparison = store.createGenericComparison(
            CreateGenericComparisonRequest(
                comparisonId = comparisonId,
                configurationSha256 = configurationSha256,
                officialIdentity = OfficialApkIdentity("a".repeat(64), 1, "com.example.app", "1.0", 1),
                buildAJobId = buildA.jobId,
                buildBJobId = buildB.jobId,
                officialVsA = RawComparisonResult.INCOMPARABLE,
                officialVsB = RawComparisonResult.MATCH,
                buildAVsB = RawComparisonResult.INCOMPARABLE,
                trustEligible = false,
                installEligible = false,
            ),
            UUID.randomUUID().toString(),
            "b".repeat(64),
        ).response
        assertEquals(0, comparison.resourceRetryCount)

        val key = UUID.randomUUID().toString()
        val first = store.createGenericResourceRetry(comparisonId, key, "c".repeat(64))
        val second = store.createGenericResourceRetry(comparisonId, key, "c".repeat(64))

        assertTrue(!first.existing)
        assertTrue(second.existing)
        assertEquals(first.response, second.response)
        assertNotEquals(buildA.jobId, first.response.buildAJobId)
        assertNotEquals(buildB.jobId, first.response.buildBJobId)
        assertEquals(GenericDockerSandboxProfile.RETRY_MEMORY_BYTES, first.response.memoryBytes)
        assertEquals(first.response.comparisonId, store.getStoredJob(first.response.buildAJobId)?.genericBuild?.comparisonId)
        assertEquals(first.response.comparisonId, store.getStoredJob(first.response.buildBJobId)?.genericBuild?.comparisonId)
        assertEquals(buildA.jobId, store.getStoredJob(first.response.buildAJobId)?.genericBuild?.retryOfJobId)
        assertEquals(buildB.jobId, store.getStoredJob(first.response.buildBJobId)?.genericBuild?.retryOfJobId)
        assertEquals(4, jobCount())
    }

    @Test
    fun `generic comparison refuses a successful-looking result for a failed attempt`() {
        val store = SQLiteJobStore(stateDirectory)
        val comparisonId = UUID.randomUUID().toString()
        val configuration = configuration()
        val canonical = GenericBuildContract.canonicalConfiguration(configuration)
        val configurationSha256 = GenericBuildContract.hash(JsonCanonicalizer(canonical).encodedUTF8)
        val buildA = createGeneric(store, comparisonId, GenericBuildAttempt.A, configurationSha256, canonical)
        val buildB = createGeneric(store, comparisonId, GenericBuildAttempt.B, configurationSha256, canonical)
        store.failIfActive(buildA.jobId, JobError("GRADLE_BUILD_FAILED", "fixture"), "fixture")
        markSandboxComplete(buildB.jobId)
        store.updateState(buildB.jobId, JobState.SUCCEEDED, 100)

        val failure = runCatching {
            store.createGenericComparison(
                CreateGenericComparisonRequest(
                    comparisonId, configurationSha256,
                    OfficialApkIdentity("a".repeat(64), 1, "com.example.app", "1.0", 1),
                    buildA.jobId, buildB.jobId,
                    RawComparisonResult.MATCH, RawComparisonResult.MATCH, RawComparisonResult.MATCH,
                    trustEligible = false, installEligible = false,
                ),
                UUID.randomUUID().toString(),
                "d".repeat(64),
            )
        }.exceptionOrNull()
        assertTrue(failure is ApiException, failure.toString())
        assertEquals("COMPARISON_BUILD_MISMATCH", (failure as ApiException).code)
    }

    private fun createGeneric(
        store: SQLiteJobStore,
        comparisonId: String,
        attempt: GenericBuildAttempt,
        configurationSha256: String,
        canonical: String,
    ): CreateJobResponse {
        val snapshot = GenericBuildSnapshot(
            comparisonId = comparisonId,
            attempt = attempt,
            configurationRevision = 1,
            configurationSha256 = configurationSha256,
            configurationCanonicalJson = canonical,
            expectedArtifactFileName = "example.apk",
        )
        return store.createGenericJob(
            CreateJobRequest(
                ExecutionMode.REAL_TRUSTED,
                "https://github.com/example/example.git",
                RequestedRevision(RevisionType.COMMIT, "a".repeat(40)),
                genericBuild = snapshot,
            ),
            UUID.randomUUID().toString(),
            "e".repeat(64),
        ).response
    }

    private fun configuration() = GenericBuildConfiguration(
        schemaVersion = 1,
        buildRoot = ".",
        modulePath = ":app",
        variant = "release",
        tasks = listOf(":app:assembleRelease"),
        javaMajor = 21,
        gradleVersion = "8.14.3",
        compileSdk = 36,
        buildToolsVersion = "36.0.0",
    )

    private fun installManagedToolchainFixture() {
        listOf(
            Triple("toolchains/jdk/21.0.12+1", "toolchains/jdk/21.0.12+1", "JDK" to "21.0.12+1"),
            Triple("toolchains/gradle/8.14.3", "toolchains/gradle/8.14.3", "GRADLE" to "8.14.3"),
            Triple("toolchains/android/platforms/android-36", "toolchains/android/platforms/android-36", "ANDROID_PLATFORM" to "36-r02"),
            Triple("toolchains/android/build-tools/36.0.0", "toolchains/android/build-tools/36.0.0", "ANDROID_BUILD_TOOLS" to "36.0.0"),
        ).forEachIndexed { index, (actualPath, relativePath, identity) ->
            Files.createDirectories(stateDirectory.resolve(actualPath))
            DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
                connection.prepareStatement(
                    "INSERT INTO toolchain_inventory(artifact_id,component,version,archive_sha256,content_manifest_sha256,installed_bytes,relative_path,state,installed_at,checked_at) VALUES(?,?,?,?,?,?,?,'VERIFIED',?,?)",
                ).use { statement ->
                    val now = Instant.now().toString()
                    statement.setString(1, "fixture-$index")
                    statement.setString(2, identity.first)
                    statement.setString(3, identity.second)
                    statement.setString(4, "f".repeat(64))
                    statement.setString(5, "f".repeat(64))
                    statement.setLong(6, 1)
                    statement.setString(7, relativePath)
                    statement.setString(8, now)
                    statement.setString(9, now)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun jobCount(): Long = DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM generic_build_requests").use { row -> row.next(); row.getLong(1) }
        }
    }

    private fun markSandboxComplete(jobId: String) {
        DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.prepareStatement("UPDATE jobs SET sandbox_cleanup_status='COMPLETE' WHERE job_id=?").use { statement ->
                statement.setString(1, jobId)
                statement.executeUpdate()
            }
        }
    }
}
