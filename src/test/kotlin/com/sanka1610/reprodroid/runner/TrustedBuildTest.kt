package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import kotlin.io.path.createDirectories

class TrustedBuildTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `real job stops at resolved commit until exact risk confirmation`() = runBlocking {
        val store = SQLiteJobStore(stateDirectory)
        val resolvedCommit = "1".repeat(40)
        val blockingProcessExecutor = object : ProcessExecutor {
            override suspend fun execute(
                command: List<String>,
                workingDirectory: Path,
                environment: Map<String, String>,
                timeout: Duration,
                captureOutput: Boolean,
                onOutput: (String) -> Unit,
            ): ProcessResult {
                delay(30_000)
                return ProcessResult(0, emptyList())
            }
        }
        val coordinator = JobCoordinator(
            store = store,
            coroutineContext = Dispatchers.IO,
            simulationStepDelayMillis = 1,
            realBuildEnabled = true,
            stateDirectory = stateDirectory,
            processExecutor = blockingProcessExecutor,
            sourceResolver = object : SourceResolver {
                override suspend fun resolve(
                    recipe: BuildRecipe,
                    revision: RequestedRevision,
                    jobId: String,
                ): String = resolvedCommit
            },
        )
        try {
            val created = coordinator.create(realRequest())
            val awaiting = withTimeout(5_000) {
                while (true) {
                    val current = coordinator.get(created.jobId)
                    if (current.state == JobState.AWAITING_CONFIRMATION) return@withTimeout current
                    delay(10)
                }
                error("unreachable")
            }
            assertEquals(resolvedCommit, awaiting.resolvedCommitSha)
            assertTrue(awaiting.requiresConfirmation)
            assertEquals(listOf(":play-services-core:assembleDefaultDebug"), awaiting.effectiveBuild?.tasks)
            assertEquals("morpheapp-microg-re-main-debug", awaiting.effectiveBuild?.recipeId)
            assertEquals("defaultDebug", awaiting.effectiveBuild?.variantName)
            assertEquals(21, awaiting.effectiveBuild?.javaMajor)

            val missingRisk = assertThrows(ApiException::class.java) {
                coordinator.confirm(created.jobId, ConfirmJobRequest(resolvedCommit, false))
            }
            assertEquals("RISK_ACKNOWLEDGEMENT_REQUIRED", missingRisk.code)
            val mismatchedCommit = assertThrows(ApiException::class.java) {
                coordinator.confirm(created.jobId, ConfirmJobRequest("2".repeat(40), true))
            }
            assertEquals("RESOLVED_COMMIT_MISMATCH", mismatchedCommit.code)

            coordinator.confirm(created.jobId, ConfirmJobRequest(resolvedCommit, true))
            withTimeout(5_000) {
                while (coordinator.get(created.jobId).state == JobState.QUEUED) delay(10)
            }
            coordinator.cancel(created.jobId)
            val cancelled = coordinator.get(created.jobId)
            assertEquals(JobState.CANCELLED, cancelled.state)
            assertFalse(cancelled.requiresConfirmation)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `wrapper verifier injects only an official checksum and verifies the jar`() = runBlocking {
        val buildRoot = stateDirectory.resolve("project").also(Path::createDirectories)
        val wrapperDirectory = buildRoot.resolve("gradle/wrapper").also(Path::createDirectories)
        val wrapperJar = wrapperDirectory.resolve("gradle-wrapper.jar")
        Files.writeString(wrapperJar, "official wrapper")
        Files.writeString(
            wrapperDirectory.resolve("gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n",
        )
        val distributionChecksum = "a".repeat(64)
        var requestedWrapperVersion: String? = null
        val verifier = WrapperVerifier(
            object : GradleChecksumSource {
                override fun distributionChecksum(gradleVersion: String, distributionType: String) =
                    distributionChecksum

                override fun wrapperJarChecksum(gradleVersion: String): String {
                    requestedWrapperVersion = gradleVersion
                    return sha256(wrapperJar)
                }
            },
        )

        val verification = verifier.verifyAndHarden(buildRoot, defaultRecipe())

        assertEquals("SUPPLIED_BY_RUNNER", verification.distributionChecksumSource)
        assertEquals("8.11.1", requestedWrapperVersion)
        assertEquals("8.11.1", verification.wrapperJarGradleVersion)
        assertTrue(
            Files.readString(wrapperDirectory.resolve("gradle-wrapper.properties"))
                .contains("distributionSha256Sum=$distributionChecksum"),
        )
    }

    @Test
    fun `wrapper checksum hardening cannot be absorbed by a trailing continuation`() = runBlocking {
        val buildRoot = stateDirectory.resolve("project").also(Path::createDirectories)
        val wrapperDirectory = buildRoot.resolve("gradle/wrapper").also(Path::createDirectories)
        val wrapperJar = wrapperDirectory.resolve("gradle-wrapper.jar")
        Files.writeString(wrapperJar, "official wrapper")
        val propertiesPath = wrapperDirectory.resolve("gradle-wrapper.properties")
        Files.writeString(
            propertiesPath,
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n" +
                "networkTimeout=10000\\",
        )
        val distributionChecksum = "a".repeat(64)
        val verifier = WrapperVerifier(
            object : GradleChecksumSource {
                override fun distributionChecksum(gradleVersion: String, distributionType: String) =
                    distributionChecksum

                override fun wrapperJarChecksum(gradleVersion: String) = sha256(wrapperJar)
            },
        )

        verifier.verifyAndHarden(buildRoot, defaultRecipe())

        val hardened = Properties().apply {
            Files.newBufferedReader(propertiesPath).use(::load)
        }
        assertEquals(distributionChecksum, hardened.getProperty("distributionSha256Sum"))
    }

    @Test
    fun `wrapper verifier rejects a jar that does not match official checksum`() = runBlocking {
        val buildRoot = stateDirectory.resolve("project").also(Path::createDirectories)
        val wrapperDirectory = buildRoot.resolve("gradle/wrapper").also(Path::createDirectories)
        Files.writeString(wrapperDirectory.resolve("gradle-wrapper.jar"), "tampered")
        Files.writeString(
            wrapperDirectory.resolve("gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n" +
                "distributionSha256Sum=${"a".repeat(64)}\n",
        )
        val verifier = WrapperVerifier(
            object : GradleChecksumSource {
                override fun distributionChecksum(gradleVersion: String, distributionType: String) = "a".repeat(64)
                override fun wrapperJarChecksum(gradleVersion: String) = "b".repeat(64)
            },
        )

        val failure = try {
            verifier.verifyAndHarden(buildRoot, defaultRecipe())
            throw AssertionError("TrustedBuildFailure was expected")
        } catch (failure: TrustedBuildFailure) {
            failure
        }
        assertEquals("WRAPPER_JAR_CHECKSUM_MISMATCH", failure.code)
    }

    @Test
    fun `real repository URL and ref must match the fixed allowlist recipe`() {
        val registry = BuildRecipeRegistry()
        assertEquals("morpheapp-microg-re-main-debug", registry.requireAllowed(
            "https://github.com/morpheapp/microg-re",
            RequestedRevision(RevisionType.BRANCH, "main"),
        ).id)
        val release = registry.requireAllowed(
            "https://github.com/MorpheApp/MicroG-RE.git",
            RequestedRevision(RevisionType.TAG, "6.1.4"),
        )
        assertEquals("morpheapp-microg-re-6.1.4-default-release", release.id)
        assertEquals("defaultRelease", release.variantName)
        assertEquals(18, release.javaMajor)
        assertEquals(listOf("clean", ":play-services-core:assembleDefaultRelease"), release.tasks)
        assertEquals("REPOSITORY_NOT_ALLOWLISTED", assertThrows(ApiException::class.java) {
            registry.requireAllowed(
                "https://github.com/example/app.git",
                RequestedRevision(RevisionType.BRANCH, "main"),
            )
        }.code)
        assertEquals("REVISION_NOT_ALLOWLISTED", assertThrows(ApiException::class.java) {
            registry.requireAllowed(
                "https://github.com/MorpheApp/MicroG-RE.git",
                RequestedRevision(RevisionType.TAG, "latest"),
            )
        }.code)
    }

    private fun realRequest() = CreateJobRequest(
        executionMode = ExecutionMode.REAL_TRUSTED,
        repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
        revision = RequestedRevision(RevisionType.BRANCH, "main"),
    )

    private fun defaultRecipe(): BuildRecipe = BuildRecipeRegistry.defaultRecipes.single {
        it.revision == RequestedRevision(RevisionType.BRANCH, "main")
    }
}
