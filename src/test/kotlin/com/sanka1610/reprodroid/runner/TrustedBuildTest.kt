package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun `job response emits dependency pinning while job requests cannot select it`() {
        val effectiveJson = Json.encodeToString(
            EffectiveBuild(
                recipeId = "recipe",
                variantName = "release",
                buildRoot = ".",
                javaMajor = 21,
                tasks = listOf("assemble"),
                dependencyPinning = DependencyPinning.NONE,
                determinism = DeterminismOptions(noBuildCache = false),
            ),
        )
        val requestJson = Json.encodeToString(realRequest())

        assertTrue(effectiveJson.contains("\"dependencyPinning\":\"NONE\""))
        assertTrue(effectiveJson.contains("\"determinism\":{\"noBuildCache\":false}"))
        assertFalse(requestJson.contains("dependencyPinning"))
        assertFalse(requestJson.contains("determinism"))
    }

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
            assertEquals(DependencyPinning.NONE, awaiting.effectiveBuild?.dependencyPinning)

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
        assertEquals(36, release.androidSdkApiLevel)
        assertEquals("36.0.0", release.buildToolsVersion)
        assertEquals(listOf("clean", ":play-services-core:assembleDefaultRelease"), release.tasks)
        assertEquals(DependencyPinning.NONE, release.dependencyPinning)
        assertTrue(BuildRecipeRegistry.defaultRecipes.all { it.dependencyPinning == DependencyPinning.NONE })
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

    @Test
    fun `recipe SDK values are validated against confined platform and build tools packages`() {
        val sdkRoot = stateDirectory.resolve("sdk").also(Path::createDirectories)
        val platform = sdkRoot.resolve("platforms/android-36").also(Path::createDirectories)
        Files.writeString(platform.resolve("android.jar"), "android")
        val buildTools = sdkRoot.resolve("build-tools/36.0.0").also(Path::createDirectories)
        val aapt2 = buildTools.resolve("aapt2")
        Files.writeString(aapt2, "aapt2")
        assertTrue(aapt2.toFile().setExecutable(true))

        val validated = validateAndroidSdkEnvironment(mapOf("ANDROID_SDK_ROOT" to sdkRoot.toString()), defaultRecipe())

        assertEquals(36, validated.apiLevel)
        assertEquals("36.0.0", validated.buildToolsVersion)
    }

    @Test
    fun `recipe SDK validation fails closed before Gradle when a package is missing`() {
        val sdkRoot = stateDirectory.resolve("sdk").also(Path::createDirectories)
        val failure = assertThrows(TrustedBuildFailure::class.java) {
            validateAndroidSdkEnvironment(mapOf("ANDROID_SDK_ROOT" to sdkRoot.toString()), defaultRecipe())
        }
        assertEquals("ANDROID_SDK_PLATFORM_INVALID", failure.code)
    }

    @Test
    fun `new recipe defaults to lockfile offline`() {
        val recipe = BuildRecipe(
            id = "fixture",
            repositoryUrl = "https://github.com/example/fixture.git",
            revision = RequestedRevision(RevisionType.TAG, "1.0"),
            variantName = "release",
            buildRoot = ".",
            javaMajor = 21,
            gradleVersion = "8.14.3",
            androidSdkApiLevel = 36,
            buildToolsVersion = "36.0.0",
            distributionType = "bin",
            wrapperJarGradleVersion = "8.14.3",
            tasks = listOf("assemble"),
            artifactPatterns = listOf("build/outputs/*.apk"),
            requireSingleApk = true,
            timeout = Duration.ofMinutes(1),
            allowRunnerSuppliedDistributionChecksum = false,
        )

        assertEquals(DependencyPinning.LOCKFILE_OFFLINE, recipe.dependencyPinning)
        assertEquals(
            listOf("--no-daemon", "--console=plain", "--offline"),
            gradleOptions(recipe),
        )
        assertEquals(
            listOf("--no-daemon", "--console=plain"),
            gradleOptions(recipe.copy(dependencyPinning = DependencyPinning.LOCKFILE)),
        )
        assertEquals(
            listOf("--no-daemon", "--console=plain"),
            gradleOptions(recipe.copy(dependencyPinning = DependencyPinning.NONE)),
        )
    }

    @Test
    fun `dependency lockfile accepts unchanged confined regular file and detects change`() {
        val sourceDirectory = stateDirectory.resolve("source").also(Path::createDirectories)
        val buildRoot = sourceDirectory.resolve("app").also(Path::createDirectories)
        val lockfile = buildRoot.resolve("gradle.lockfile")
        Files.writeString(lockfile, "example:library:1.0=runtimeClasspath\n")
        val verifier = DependencyLockfileVerifier()

        val preHash = verifier.preBuildHash(sourceDirectory, buildRoot)
        assertEquals(preHash, verifier.postBuildHash(sourceDirectory, buildRoot))

        Files.writeString(lockfile, "example:library:2.0=runtimeClasspath\n")
        val postHash = verifier.postBuildHash(sourceDirectory, buildRoot)
        assertFalse(preHash == postHash)
    }

    @Test
    fun `dependency lockfile rejects missing directory symlink and oversized files`() {
        val sourceDirectory = stateDirectory.resolve("source").also(Path::createDirectories)
        val buildRoot = sourceDirectory.resolve("app").also(Path::createDirectories)
        val verifier = DependencyLockfileVerifier(maxBytes = 4)
        val lockfile = buildRoot.resolve("gradle.lockfile")

        assertEquals("DEPENDENCY_LOCKFILE_MISSING", lockFailure {
            verifier.preBuildHash(sourceDirectory, buildRoot)
        }.code)

        Files.createDirectory(lockfile)
        assertEquals("DEPENDENCY_LOCKFILE_INVALID", lockFailure {
            verifier.preBuildHash(sourceDirectory, buildRoot)
        }.code)
        Files.delete(lockfile)

        val target = sourceDirectory.resolve("target.lock")
        Files.writeString(target, "safe")
        Files.createSymbolicLink(lockfile, target)
        assertEquals("DEPENDENCY_LOCKFILE_INVALID", lockFailure {
            verifier.preBuildHash(sourceDirectory, buildRoot)
        }.code)
        Files.delete(lockfile)

        Files.writeString(lockfile, "large")
        assertEquals("DEPENDENCY_LOCKFILE_TOO_LARGE", lockFailure {
            verifier.preBuildHash(sourceDirectory, buildRoot)
        }.code)
    }

    @Test
    fun `dependency lockfile post build invalidity is reported as changed`() {
        val sourceDirectory = stateDirectory.resolve("source").also(Path::createDirectories)
        val buildRoot = sourceDirectory.resolve("app").also(Path::createDirectories)
        val lockfile = buildRoot.resolve("gradle.lockfile")
        Files.writeString(lockfile, "locked")
        val verifier = DependencyLockfileVerifier()
        verifier.preBuildHash(sourceDirectory, buildRoot)

        Files.delete(lockfile)
        assertEquals("DEPENDENCY_LOCKFILE_CHANGED", lockFailure {
            verifier.postBuildHash(sourceDirectory, buildRoot)
        }.code)
    }

    @Test
    fun `dependency lockfile build root path escape is rejected`() {
        val sourceDirectory = stateDirectory.resolve("source").also(Path::createDirectories)
        val outsideBuildRoot = stateDirectory.resolve("outside").also(Path::createDirectories)
        Files.writeString(outsideBuildRoot.resolve("gradle.lockfile"), "locked")

        val failure = lockFailure {
            DependencyLockfileVerifier().preBuildHash(sourceDirectory, outsideBuildRoot)
        }

        assertEquals("DEPENDENCY_LOCKFILE_INVALID", failure.code)
        assertFalse(failure.message.orEmpty().contains(stateDirectory.toString()))
    }

    @Test
    fun `dependency lockfile post build rejects symlink directory and oversized replacement`() {
        val sourceDirectory = stateDirectory.resolve("source").also(Path::createDirectories)
        val buildRoot = sourceDirectory.resolve("app").also(Path::createDirectories)
        val lockfile = buildRoot.resolve("gradle.lockfile")
        val verifier = DependencyLockfileVerifier(maxBytes = 4)

        Files.writeString(lockfile, "safe")
        verifier.preBuildHash(sourceDirectory, buildRoot)
        Files.delete(lockfile)
        Files.writeString(sourceDirectory.resolve("replacement.lock"), "safe")
        Files.createSymbolicLink(lockfile, sourceDirectory.resolve("replacement.lock"))
        assertEquals("DEPENDENCY_LOCKFILE_CHANGED", lockFailure {
            verifier.postBuildHash(sourceDirectory, buildRoot)
        }.code)

        Files.delete(lockfile)
        Files.createDirectory(lockfile)
        assertEquals("DEPENDENCY_LOCKFILE_CHANGED", lockFailure {
            verifier.postBuildHash(sourceDirectory, buildRoot)
        }.code)

        Files.delete(lockfile)
        Files.writeString(lockfile, "large")
        assertEquals("DEPENDENCY_LOCKFILE_CHANGED", lockFailure {
            verifier.postBuildHash(sourceDirectory, buildRoot)
        }.code)
    }

    @Test
    fun `determinism injects canonical epoch locale and no build cache only when configured`() {
        val base = mapOf(
            "PATH" to "/usr/bin",
            "LANG" to "ja_JP.UTF-8",
            "LC_ALL" to "ja_JP.UTF-8",
        )
        val determinism = DeterminismOptions(
            sourceDateEpoch = 1_777_393_787,
            noBuildCache = true,
            fixedLocale = FixedLocale.C_UTF_8,
        )
        val recipe = defaultRecipe().copy(determinism = determinism)

        val gradleEnvironment = deterministicGradleEnvironment(base, determinism)

        assertEquals("1777393787", gradleEnvironment["SOURCE_DATE_EPOCH"])
        assertEquals("C.UTF-8", gradleEnvironment["LANG"])
        assertEquals("C.UTF-8", gradleEnvironment["LC_ALL"])
        assertEquals("ja_JP.UTF-8", base["LANG"])
        assertEquals(
            listOf("--no-daemon", "--console=plain", "--no-build-cache"),
            gradleOptions(recipe),
        )
        assertFalse(deterministicGradleEnvironment(base, DeterminismOptions(noBuildCache = false))
            .containsKey("SOURCE_DATE_EPOCH"))
    }

    @Test
    fun `determinism recipe rejects negative epoch and reserved cache options`() {
        assertThrows(IllegalArgumentException::class.java) {
            BuildRecipeRegistry(
                listOf(
                    defaultRecipe().copy(
                        determinism = DeterminismOptions(sourceDateEpoch = -1, noBuildCache = false),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuildRecipeRegistry(listOf(defaultRecipe().copy(tasks = listOf("--build-cache", "assemble"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuildRecipeRegistry(listOf(defaultRecipe().copy(tasks = listOf("--no-build-cache", "assemble"))))
        }
    }

    @Test
    fun `sqlite persists effective determinism without registry inference`() {
        val store = SQLiteJobStore(stateDirectory)
        val created = store.createJob(realRequest())
        val determinism = DeterminismOptions(
            sourceDateEpoch = 1_777_393_787,
            noBuildCache = true,
            fixedLocale = FixedLocale.C_UTF_8,
        )
        assertTrue(store.beginResolvingRealJob(created.jobId, defaultRecipe().copy(determinism = determinism)))
        assertEquals(determinism, requireNotNull(requireNotNull(store.getJob(created.jobId)).effectiveBuild).determinism)

        val restarted = SQLiteJobStore(stateDirectory)
        assertEquals(determinism, requireNotNull(requireNotNull(restarted.getJob(created.jobId)).effectiveBuild).determinism)
    }

    private fun realRequest() = CreateJobRequest(
        executionMode = ExecutionMode.REAL_TRUSTED,
        repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
        revision = RequestedRevision(RevisionType.BRANCH, "main"),
    )

    private fun defaultRecipe(): BuildRecipe = BuildRecipeRegistry.defaultRecipes.single {
        it.revision == RequestedRevision(RevisionType.BRANCH, "main")
    }

    private fun lockFailure(block: () -> Unit): TrustedBuildFailure = try {
        block()
        throw AssertionError("TrustedBuildFailure was expected")
    } catch (failure: TrustedBuildFailure) {
        failure
    }
}
