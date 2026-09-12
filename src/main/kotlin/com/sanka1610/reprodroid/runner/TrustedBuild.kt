package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import org.slf4j.LoggerFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal class TrustedBuildFailure(
    val code: String,
    override val message: String,
) : RuntimeException(message)

private val trustedBuildLogger = LoggerFactory.getLogger("ReproDroidRunner")

internal interface SourceResolver {
    suspend fun resolve(recipe: BuildRecipe, revision: RequestedRevision, jobId: String): String
}

internal class GitSourceResolver(
    private val processExecutor: ProcessExecutor,
    private val store: SQLiteJobStore,
    private val stateDirectory: Path,
) : SourceResolver {
    override suspend fun resolve(recipe: BuildRecipe, revision: RequestedRevision, jobId: String): String {
        if (revision.type == RevisionType.COMMIT) {
            if (!COMMIT_SHA.matches(revision.value)) {
                throw TrustedBuildFailure("INVALID_COMMIT_SHA", "An allowlisted commit must be a full SHA-1 value.")
            }
            return revision.value.lowercase()
        }
        val refs = when (revision.type) {
            RevisionType.BRANCH -> listOf("refs/heads/${revision.value}")
            RevisionType.TAG -> listOf("refs/tags/${revision.value}", "refs/tags/${revision.value}^{}")
            RevisionType.COMMIT -> error("handled above")
        }
        val commandDirectory = stateDirectory.resolve("command-home").also(Path::createDirectories)
        val result = try {
            processExecutor.execute(
                command = listOf("git", "ls-remote", "--exit-code", recipe.repositoryUrl) + refs,
                workingDirectory = stateDirectory,
                environment = restrictedEnvironment(commandDirectory),
                timeout = Duration.ofMinutes(2),
                captureOutput = true,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ProcessTimeoutException) {
            throw TrustedBuildFailure("REVISION_RESOLUTION_TIMEOUT", "The allowlisted ref resolution timed out.")
        } catch (_: Throwable) {
            throw TrustedBuildFailure("GIT_UNAVAILABLE", "Git could not be started for ref resolution.")
        }
        if (result.exitCode != 0) {
            throw TrustedBuildFailure("REVISION_RESOLUTION_FAILED", "The allowlisted ref could not be resolved.")
        }
        val candidates = result.outputLines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && COMMIT_SHA.matches(parts[0])) parts[0].lowercase() to parts[1] else null
        }
        val peeledTagRef = refs.lastOrNull()?.takeIf { it.endsWith("^{}") }
        val resolved = candidates.firstOrNull { it.second == peeledTagRef }?.first
            ?: candidates.firstOrNull()?.first
            ?: throw TrustedBuildFailure("REVISION_RESOLUTION_FAILED", "Git returned no commit for the allowlisted ref.")
        store.appendLog(jobId, LogLevel.INFO, "Resolved ${revision.type.name.lowercase()} ${revision.value} to commit $resolved.")
        return resolved
    }

    private companion object {
        val COMMIT_SHA = Regex("[0-9a-fA-F]{40}")
    }
}

@Serializable
internal data class WrapperVerification(
    val gradleVersion: String,
    val distributionUrl: String,
    val distributionSha256: String,
    val distributionChecksumSource: String,
    val wrapperJarGradleVersion: String,
    val wrapperJarSha256: String,
)

internal interface GradleChecksumSource {
    fun distributionChecksum(gradleVersion: String, distributionType: String): String
    fun wrapperJarChecksum(gradleVersion: String): String
}

internal class OfficialGradleChecksumSource : GradleChecksumSource {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override fun distributionChecksum(gradleVersion: String, distributionType: String): String =
        checksum("https://services.gradle.org/distributions/gradle-$gradleVersion-$distributionType.zip.sha256")

    override fun wrapperJarChecksum(gradleVersion: String): String =
        checksum("https://services.gradle.org/distributions/gradle-$gradleVersion-wrapper.jar.sha256")

    private fun checksum(url: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "text/plain")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: IOException) {
            throw TrustedBuildFailure("OFFICIAL_CHECKSUM_UNAVAILABLE", "Gradle checksum service could not be reached.")
        }
        if (response.statusCode() !in 200..299) {
            throw TrustedBuildFailure("OFFICIAL_CHECKSUM_UNAVAILABLE", "Gradle checksum service returned HTTP ${response.statusCode()}.")
        }
        return response.body().trim().lowercase().takeIf(SHA256::matches)
            ?: throw TrustedBuildFailure("OFFICIAL_CHECKSUM_INVALID", "Gradle checksum service returned an invalid SHA-256 value.")
    }
}

internal class WrapperVerifier(
    private val checksumSource: GradleChecksumSource = OfficialGradleChecksumSource(),
) {
    suspend fun verifyAndHarden(buildRoot: Path, recipe: BuildRecipe): WrapperVerification = runInterruptible {
        val wrapperDirectory = confinedPath(buildRoot, "gradle/wrapper")
        val propertiesPath = confinedPath(wrapperDirectory, "gradle-wrapper.properties")
        val wrapperJarPath = confinedPath(wrapperDirectory, "gradle-wrapper.jar")
        if (!propertiesPath.isRegularFile() || !wrapperJarPath.isRegularFile()) {
            throw TrustedBuildFailure("WRAPPER_FILES_MISSING", "The fixed build root does not contain Gradle Wrapper files.")
        }

        val properties = Properties().apply {
            Files.newBufferedReader(propertiesPath).use(::load)
        }
        val distributionUrl = properties.getProperty("distributionUrl")
            ?: throw TrustedBuildFailure("WRAPPER_DISTRIBUTION_URL_MISSING", "distributionUrl is missing.")
        val sourceDistributionType = validateDistributionUrl(distributionUrl, recipe)
        val officialDistribution = checksumSource.distributionChecksum(recipe.gradleVersion, sourceDistributionType)
        val repositoryChecksum = properties.getProperty("distributionSha256Sum")?.trim()?.lowercase()
        val checksumSourceLabel = if (repositoryChecksum == null) {
            if (!recipe.allowRunnerSuppliedDistributionChecksum) {
                throw TrustedBuildFailure("DISTRIBUTION_CHECKSUM_MISSING", "distributionSha256Sum is required by the recipe.")
            }
            persistDistributionChecksum(propertiesPath, properties, officialDistribution)
            "SUPPLIED_BY_RUNNER"
        } else {
            if (!SHA256.matches(repositoryChecksum) || repositoryChecksum != officialDistribution) {
                throw TrustedBuildFailure("DISTRIBUTION_CHECKSUM_MISMATCH", "distributionSha256Sum does not match Gradle's official value.")
            }
            "REPOSITORY"
        }

        val actualWrapperJar = sha256(wrapperJarPath)
        val officialWrapperJar = checksumSource.wrapperJarChecksum(recipe.wrapperJarGradleVersion)
        if (actualWrapperJar != officialWrapperJar) {
            throw TrustedBuildFailure("WRAPPER_JAR_CHECKSUM_MISMATCH", "gradle-wrapper.jar does not match Gradle's official checksum.")
        }
        WrapperVerification(
            gradleVersion = recipe.gradleVersion,
            distributionUrl = distributionUrl,
            distributionSha256 = officialDistribution,
            distributionChecksumSource = checksumSourceLabel,
            wrapperJarGradleVersion = recipe.wrapperJarGradleVersion,
            wrapperJarSha256 = actualWrapperJar,
        )
    }

    private fun persistDistributionChecksum(
        propertiesPath: Path,
        properties: Properties,
        officialDistribution: String,
    ) {
        properties.setProperty("distributionSha256Sum", officialDistribution)
        try {
            Files.newBufferedWriter(propertiesPath).use { writer ->
                properties.store(writer, "Hardened by ReproDroid Runner")
            }
            val hardenedProperties = Properties().apply {
                Files.newBufferedReader(propertiesPath).use(::load)
            }
            if (hardenedProperties.getProperty("distributionSha256Sum") != officialDistribution) {
                throw TrustedBuildFailure(
                    "DISTRIBUTION_CHECKSUM_HARDENING_FAILED",
                    "Runner could not persist the official distribution checksum as an independent property.",
                )
            }
        } catch (failure: TrustedBuildFailure) {
            throw failure
        } catch (_: IOException) {
            throw TrustedBuildFailure(
                "WRAPPER_PROPERTIES_WRITE_FAILED",
                "Runner could not harden gradle-wrapper.properties with the official distribution checksum.",
            )
        }
    }

    private fun validateDistributionUrl(distributionUrl: String, recipe: BuildRecipe): String {
        val uri = runCatching { URI(distributionUrl) }.getOrNull()
            ?: throw TrustedBuildFailure("INVALID_DISTRIBUTION_URL", "distributionUrl is not a valid URI.")
        // Managed generic builds execute the catalog-mounted GradleMain rather than the source wrapper.
        // The source wrapper remains provenance and may name either official Gradle distribution flavor.
        val allowedDistributionTypes = if (recipe.managedToolchains) {
            MANAGED_WRAPPER_DISTRIBUTION_TYPES
        } else {
            setOf(recipe.distributionType)
        }
        val distributionType = allowedDistributionTypes.singleOrNull { type ->
            uri.path == "/distributions/gradle-${recipe.gradleVersion}-$type.zip"
        }
        if (
            uri.scheme?.lowercase() != "https" ||
            uri.host?.lowercase() !in OFFICIAL_GRADLE_HOSTS ||
            uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null ||
            distributionType == null
        ) {
            throw TrustedBuildFailure("INVALID_DISTRIBUTION_URL", "distributionUrl does not match the fixed Gradle recipe.")
        }
        return distributionType
    }

    private companion object {
        val OFFICIAL_GRADLE_HOSTS = setOf("services.gradle.org", "downloads.gradle.org")
        val MANAGED_WRAPPER_DISTRIBUTION_TYPES = setOf("bin", "all")
    }
}

internal class DependencyLockfileVerifier(
    private val maxBytes: Long = MAX_DEPENDENCY_LOCKFILE_BYTES,
) {
    fun preBuildHash(sourceDirectory: Path, buildRoot: Path): String =
        inspect(sourceDirectory, buildRoot, postBuild = false)

    fun postBuildHash(sourceDirectory: Path, buildRoot: Path): String =
        try {
            inspect(sourceDirectory, buildRoot, postBuild = true)
        } catch (_: TrustedBuildFailure) {
            throw TrustedBuildFailure(
                "DEPENDENCY_LOCKFILE_CHANGED",
                "The dependency lockfile changed or became invalid during the Gradle build.",
            )
        }

    private fun inspect(sourceDirectory: Path, buildRoot: Path, postBuild: Boolean): String {
        val sourceRoot = try {
            sourceDirectory.toRealPath()
        } catch (_: IOException) {
            throw invalid(postBuild)
        }
        val realBuildRoot = try {
            buildRoot.toRealPath()
        } catch (_: IOException) {
            throw invalid(postBuild)
        }
        if (!realBuildRoot.startsWith(sourceRoot)) throw invalid(postBuild)
        val lockfile = realBuildRoot.resolve("gradle.lockfile").normalize()
        if (!lockfile.startsWith(realBuildRoot)) throw invalid(postBuild)
        if (!Files.exists(lockfile, LinkOption.NOFOLLOW_LINKS)) {
            if (postBuild) throw invalid(postBuild)
            throw TrustedBuildFailure(
                "DEPENDENCY_LOCKFILE_MISSING",
                "The fixed build recipe requires a dependency lockfile.",
            )
        }
        if (!Files.isRegularFile(lockfile, LinkOption.NOFOLLOW_LINKS)) throw invalid(postBuild)
        val declaredSize = try {
            Files.size(lockfile)
        } catch (_: IOException) {
            throw invalid(postBuild)
        }
        if (declaredSize > maxBytes) {
            if (postBuild) throw invalid(postBuild)
            throw TrustedBuildFailure(
                "DEPENDENCY_LOCKFILE_TOO_LARGE",
                "The dependency lockfile exceeds the 8 MiB safety limit.",
            )
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(lockfile, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        if (postBuild) throw invalid(postBuild)
                        throw TrustedBuildFailure(
                            "DEPENDENCY_LOCKFILE_TOO_LARGE",
                            "The dependency lockfile exceeds the 8 MiB safety limit.",
                        )
                    }
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } catch (failure: TrustedBuildFailure) {
            throw failure
        } catch (_: Throwable) {
            throw invalid(postBuild)
        }
    }

    private fun invalid(postBuild: Boolean): TrustedBuildFailure =
        if (postBuild) {
            TrustedBuildFailure(
                "DEPENDENCY_LOCKFILE_CHANGED",
                "The dependency lockfile changed or became invalid during the Gradle build.",
            )
        } else {
            TrustedBuildFailure(
                "DEPENDENCY_LOCKFILE_INVALID",
                "The dependency lockfile is not a confined non-symlink regular file.",
            )
        }

    private companion object {
        const val MAX_DEPENDENCY_LOCKFILE_BYTES = 8L * 1024L * 1024L
    }
}

internal fun gradleOptions(recipe: BuildRecipe): List<String> = buildList {
    add("--no-daemon")
    add("--console=plain")
    if (recipe.dependencyPinning == DependencyPinning.LOCKFILE_OFFLINE) add("--offline")
    if (recipe.determinism.noBuildCache) add("--no-build-cache")
}

internal fun deterministicGradleEnvironment(
    restrictedEnvironment: Map<String, String>,
    determinism: DeterminismOptions,
): Map<String, String> = restrictedEnvironment.toMutableMap().apply {
    determinism.sourceDateEpoch?.let { put("SOURCE_DATE_EPOCH", it.toString()) }
    determinism.fixedLocale?.let { fixedLocale ->
        val value = when (fixedLocale) {
            FixedLocale.C_UTF_8 -> "C.UTF-8"
        }
        put("LANG", value)
        put("LC_ALL", value)
    }
}

internal class TrustedBuildExecutor(
    private val store: SQLiteJobStore,
    private val processExecutor: ProcessExecutor,
    private val wrapperVerifier: WrapperVerifier,
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val dependencyLockfileVerifier: DependencyLockfileVerifier = DependencyLockfileVerifier(),
    private val sourceScanner: SourceScanner = SourceScanner(),
    private val dockerExecutor: DockerBuildExecutor? = null,
) {
    suspend fun execute(job: StoredJob, recipe: BuildRecipe) {
        val snapshot = job.sandbox ?: throw invalidSandboxSnapshot()
        snapshot.validateAnyProfile()
        val dockerMode = snapshot.jobSandbox.mode == BuildSandboxMode.DOCKER
        if (dockerMode && dockerExecutor == null) {
            throw TrustedBuildFailure("SANDBOX_START_FAILED", "The Docker build executor is not available.")
        }
        if (job.genericBuild == null) snapshot.validatedProfile()?.requireSupported(recipe)
        if (job.genericBuild != null && !dockerMode) {
            throw TrustedBuildFailure("GENERIC_DOCKER_REQUIRED", "Generic builds cannot execute through HOST fallback.")
        }
        val resolvedCommit = job.resolvedCommitSha
            ?: throw TrustedBuildFailure("RESOLVED_COMMIT_MISSING", "The confirmed job has no resolved commit.")
        val workspace = stateDirectory.resolve("workspaces").resolve(job.jobId).normalize()
        val sourceDirectory = confinedPath(workspace, "source")
        val homeDirectory = confinedPath(workspace, "home").also(Path::createDirectories)
        val gradleUserHome = confinedPath(workspace, "gradle-user-home").also(Path::createDirectories)
        workspace.createDirectories()
        val genericConfiguration = job.genericBuild?.let { GenericBuildContract.validate(job.repositoryUrl, job.revision.value, it) }
        val managedToolchains = genericConfiguration?.let(store::requireManagedToolchains)
        val buildJava = resolveBuildJavaRuntime(recipe, managedToolchains?.javaHome)
        val environment = restrictedEnvironment(homeDirectory, gradleUserHome, buildJava.home).toMutableMap().apply {
            managedToolchains?.let {
                put("ANDROID_HOME", it.androidSdkRoot.toString())
                put("ANDROID_SDK_ROOT", it.androidSdkRoot.toString())
            }
        }
        val storedScan = store.getStoredSourceScan(job.jobId)
        if (storedScan?.reviewed == true) {
            if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceDirectory)) {
                throw TrustedBuildFailure("SOURCE_SCAN_INVALID", "The reviewed source checkout is no longer available.")
            }
            verifyCheckout(sourceDirectory, workspace, environment, resolvedCommit, requireClean = true)
            if (storedScan.detail.resolvedCommitSha != resolvedCommit) {
                throw TrustedBuildFailure("SOURCE_SCAN_INVALID", "The stored source scan is not bound to the confirmed commit.")
            }
            store.appendLog(job.jobId, LogLevel.INFO, "Reviewed source scan restored for the unchanged detached checkout.")
        } else {
            if (storedScan != null || Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw TrustedBuildFailure("WORKSPACE_ALREADY_EXISTS", "The job workspace already contains unexpected source state.")
            }
            transition(job.jobId, JobState.CLONING, 25, "Cloning the allowlisted repository without submodules or Git LFS smudging.")
            runChecked(
                jobId = job.jobId,
                command = listOf(
                    "git", "-c", "filter.lfs.smudge=", "-c", "filter.lfs.required=false",
                    "clone", "--no-checkout", "--no-recurse-submodules", "--", recipe.repositoryUrl,
                    sourceDirectory.toString(),
                ),
                workingDirectory = workspace,
                environment = environment,
                timeout = minOf(recipe.timeout, Duration.ofMinutes(10)),
                failureCode = "GIT_CLONE_FAILED",
                outputPrefix = "git",
            )
            runChecked(
                jobId = job.jobId,
                command = listOf(
                    "git", "-c", "advice.detachedHead=false", "-c", "filter.lfs.smudge=",
                    "-c", "filter.lfs.required=false", "-C", sourceDirectory.toString(),
                    "checkout", "--detach", resolvedCommit,
                ),
                workingDirectory = workspace,
                environment = environment,
                timeout = Duration.ofMinutes(5),
                failureCode = "GIT_CHECKOUT_FAILED",
                outputPrefix = "git",
            )
            verifyCheckout(sourceDirectory, workspace, environment, resolvedCommit, requireClean = false)
            store.appendLog(job.jobId, LogLevel.INFO, "Detached checkout verified at commit $resolvedCommit.")
            transition(job.jobId, JobState.SCANNING_SOURCE, 32, "Scanning the detached checkout with reprodroid-static-v1 before SDK and Wrapper verification.")
            val scanResult = sourceScanner.scan(job.jobId, sourceDirectory, resolvedCommit)
            val persisted = try {
                store.recordSourceScanResult(scanResult)
            } catch (_: Throwable) {
                throw TrustedBuildFailure(
                    "SOURCE_SCAN_PERSISTENCE_FAILED",
                    "Runner could not persist the source scan result atomically.",
                )
            }
            if (!persisted) throw CancellationException("Job changed state before the source scan result was persisted.")
            store.appendLog(
                job.jobId,
                LogLevel.INFO,
                "Source scan completed: ${scanResult.detail.summary.scannedFiles} files, " +
                    "${scanResult.detail.summary.findingCount} finding(s), result SHA-256 ${scanResult.detail.resultSha256}.",
            )
            if (scanResult.requiresReview) {
                store.appendLog(
                    job.jobId,
                    LogLevel.WARN,
                    "Build paused before SDK and Wrapper verification until the client reviews the source scan findings.",
                )
                return
            }
        }
        val validatedAndroidSdk = validateAndroidSdkEnvironment(environment, recipe)
        store.appendLog(
            job.jobId,
            LogLevel.INFO,
            "Validated Android SDK API ${validatedAndroidSdk.apiLevel} and Build Tools ${validatedAndroidSdk.buildToolsVersion}.",
        )

        transition(job.jobId, JobState.VERIFYING_WRAPPER, 40, "Verifying Gradle distribution and Wrapper JAR checksums.")
        val buildRoot = confinedPath(sourceDirectory, recipe.buildRoot)
        if (!Files.isDirectory(buildRoot)) {
            throw TrustedBuildFailure("BUILD_ROOT_MISSING", "The fixed build root does not exist.")
        }
        val dependencyLockPreHash = if (recipe.dependencyPinning == DependencyPinning.NONE) {
            null
        } else {
            dependencyLockfileVerifier.preBuildHash(sourceDirectory, buildRoot).also { hash ->
                if (!store.recordDependencyLockPreBuild(job.jobId, hash)) {
                    throw TrustedBuildFailure(
                        "DEPENDENCY_LOCK_AUDIT_PERSISTENCE_FAILED",
                        "Runner could not persist the dependency lockfile audit result.",
                    )
                }
                store.appendLog(
                    job.jobId,
                    LogLevel.INFO,
                    "Dependency lockfile pre-build SHA-256 $hash verified for ${recipe.dependencyPinning.name}.",
                )
            }
        }
        val wrapper = wrapperVerifier.verifyAndHarden(buildRoot, recipe)
        if (!store.recordWrapperVerification(job.jobId, wrapper)) {
            throw TrustedBuildFailure(
                "WRAPPER_AUDIT_PERSISTENCE_FAILED",
                "Runner could not persist the Wrapper verification result.",
            )
        }
        store.appendLog(
            job.jobId,
            LogLevel.INFO,
            "Wrapper verified: Gradle ${wrapper.gradleVersion}, distribution ${wrapper.distributionSha256} " +
                "(${wrapper.distributionChecksumSource}), official Wrapper JAR ${wrapper.wrapperJarGradleVersion} " +
                "${wrapper.wrapperJarSha256}.",
        )
        val gradleEnvironment = deterministicGradleEnvironment(environment, recipe.determinism)
        store.requireCurrentManifest(job.jobId)
        recipe.determinism.fixedLocale?.let {
            val charmap = captureChecked(
                command = listOf("locale", "charmap"),
                workingDirectory = workspace,
                environment = gradleEnvironment,
                timeout = Duration.ofMinutes(1),
                failureCode = "DETERMINISM_LOCALE_UNAVAILABLE",
            ).singleOrNull()?.trim()?.replace("-", "")?.uppercase()
            if (charmap != "UTF8") {
                throw TrustedBuildFailure(
                    "DETERMINISM_LOCALE_UNAVAILABLE",
                    "The fixed recipe locale is not available as UTF-8.",
                )
            }
            store.appendLog(job.jobId, LogLevel.INFO, "Validated fixed Gradle process locale C.UTF-8.")
        }
        val javaExecutable = buildJava.executable.toString()
        val wrapperJar = confinedPath(buildRoot, "gradle/wrapper/gradle-wrapper.jar")
        var gradleFailure: Throwable? = null
        var dockerResult: DockerBuildResult? = null
        if (dockerMode) {
            dockerResult = requireNotNull(dockerExecutor).execute(
                job, recipe, sourceDirectory.toAbsolutePath(), homeDirectory.toAbsolutePath(), gradleUserHome.toAbsolutePath(), buildJava,
                Path.of(requireNotNull(environment["ANDROID_SDK_ROOT"] ?: environment["ANDROID_HOME"])).toAbsolutePath(),
                managedToolchains,
            )
            // No mutable output may be inspected until execute() has completed its removal proof.
            listOf(sourceDirectory, homeDirectory, gradleUserHome).forEach {
                SandboxOutputImport(it, SandboxImportLimits.DEPENDENCIES).validateDirectory()
            }
            gradleFailure = dockerResult.failure
        } else try {
            transition(job.jobId, JobState.BUILDING, 55, "Starting confirmed Gradle tasks: ${recipe.tasks.joinToString(" ")}.")
            runChecked(
                jobId = job.jobId,
                command = listOf(
                    javaExecutable,
                    "-Dgradle.user.home=$gradleUserHome",
                    "-classpath", wrapperJar.toString(),
                    "org.gradle.wrapper.GradleWrapperMain",
                ) + gradleOptions(recipe) + recipe.tasks,
                workingDirectory = buildRoot,
                environment = gradleEnvironment,
                timeout = recipe.timeout,
                failureCode = "GRADLE_BUILD_FAILED",
                outputPrefix = "gradle",
            )
        } catch (failure: Throwable) {
            gradleFailure = failure
        }
        var dependencyLockFailure: Throwable? = null
        if (dependencyLockPreHash != null) {
            try {
                val postHash = dependencyLockfileVerifier.postBuildHash(sourceDirectory, buildRoot)
                if (!store.recordDependencyLockPostBuild(job.jobId, postHash)) {
                    throw TrustedBuildFailure(
                        "DEPENDENCY_LOCK_AUDIT_PERSISTENCE_FAILED",
                        "Runner could not persist the dependency lockfile audit result.",
                    )
                }
                if (postHash != dependencyLockPreHash) {
                    throw TrustedBuildFailure(
                        "DEPENDENCY_LOCKFILE_CHANGED",
                        "The dependency lockfile changed during the Gradle build.",
                    )
                }
                store.appendLog(
                    job.jobId,
                    LogLevel.INFO,
                    "Dependency lockfile post-build SHA-256 $postHash matches the pre-build audit value.",
                )
            } catch (failure: Throwable) {
                dependencyLockFailure = failure
            }
        }
        if (gradleFailure is CancellationException) {
            if (dependencyLockFailure != null) {
                store.appendLog(
                    job.jobId,
                    LogLevel.WARN,
                    "Dependency lockfile post-build verification did not complete cleanly after cancellation.",
                )
            }
            throw gradleFailure
        }
        dependencyLockFailure?.let { throw it }
        gradleFailure?.let { throw it }

        transition(job.jobId, JobState.DISCOVERING_ARTIFACTS, 90, "Discovering APKs using the fixed artifact recipe.")
        val dockerOutputRoot = if (genericConfiguration == null) {
            buildRoot.resolve("play-services-core/build/outputs/apk/default/release")
        } else {
            val moduleDirectory = genericConfiguration.modulePath.removePrefix(":").replace(':', '/')
            confinedPath(buildRoot, listOf(moduleDirectory, "build/outputs/apk").filter(String::isNotBlank).joinToString("/"))
        }
        val apkImport = if (dockerMode) SandboxOutputImport(dockerOutputRoot, SandboxImportLimits.APK) else null
        val importedCandidates = apkImport?.importApks(
            stateDirectory.resolve("sandbox-imports").resolve(job.jobId).also(Path::createDirectories),
        ) { relative ->
            job.genericBuild != null || relative.nameCount == 1
        }
        val importedApks = importedCandidates?.let { candidates ->
            job.genericBuild?.let { generic ->
                selectGenericImportedApk(candidates, generic.expectedArtifactFileName)
            } ?: candidates
        }
        if (importedApks != null && importedApks.size != 1) {
            val code = if (importedApks.isEmpty()) "APK_NOT_FOUND" else "UNEXPECTED_APK_COUNT"
            val message = if (job.genericBuild == null) {
                "The sandbox recipe requires exactly one APK."
            } else {
                "Generic artifact selection requires exactly one APK matching the persisted file name."
            }
            throw TrustedBuildFailure(code, message)
        }
        val artifactPaths = importedApks?.map { it.first } ?: discoverArtifacts(buildRoot, recipe)
        val discoveredArtifacts = artifactPaths.map { artifactPath ->
            ArtifactMetadata(
                artifactId = UUID.randomUUID().toString(),
                fileName = importedApks?.single { it.first == artifactPath }?.second?.path?.substringAfterLast('/') ?: artifactPath.name,
                sizeBytes = Files.size(artifactPath),
                sha256 = sha256(artifactPath),
                packageName = "",
                versionName = "",
                versionCode = 0,
            )
        }
        val generatedAt = Instant.now(clock).toString()
        val manifest = BuildEnvironmentManifest(
            generatedAt = generatedAt,
            jobId = job.jobId,
            recipeId = recipe.id,
            repositoryUrl = recipe.repositoryUrl,
            requestedRevision = job.revision,
            resolvedCommitSha = resolvedCommit,
            buildRoot = recipe.buildRoot,
            tasks = recipe.tasks,
            gradleVersion = wrapper.gradleVersion,
            javaVersion = dockerResult?.audit?.java?.version ?: buildJava.version,
            javaVendor = dockerResult?.audit?.java?.vendor ?: buildJava.vendor,
            operatingSystem = dockerResult?.audit?.operatingSystem ?: "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            androidSdk = environment["ANDROID_SDK_ROOT"] ?: environment["ANDROID_HOME"],
            androidSdkApiLevel = validatedAndroidSdk.apiLevel,
            buildToolsVersion = validatedAndroidSdk.buildToolsVersion,
            sandbox = dockerResult?.audit?.sandbox ?: SandboxEvidence(BuildSandboxMode.HOST),
            dockerAudit = dockerResult?.audit,
            controllerJava = PublicJavaRuntime(System.getProperty("java.version"), System.getProperty("java.vendor")),
            controllerOperatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            determinism = recipe.determinism,
            wrapper = wrapper,
            dependencies = if (dockerMode) SandboxOutputImport(
                gradleUserHome.resolve("caches/modules-2/files-2.1"), SandboxImportLimits.DEPENDENCIES,
            ).dependencies() else captureDependencies(gradleUserHome),
            artifacts = artifactPaths.zip(discoveredArtifacts).map { (path, metadata) ->
                ManifestFile(
                    if (dockerMode) importedApks?.single { it.first == path }?.second?.path ?: metadata.fileName
                    else buildRoot.relativize(path).toString().replace('\\', '/'), metadata.sizeBytes, metadata.sha256,
                )
            },
            genericBuild = job.genericBuild,
            discovery = store.genericDiscovery(job.jobId),
        )
        val manifestDirectory = stateDirectory.resolve("manifests").resolve(job.jobId).also(Path::createDirectories)
        val manifestPath = confinedPath(manifestDirectory, "reprodroid-build.json")
        Files.writeString(manifestPath, MANIFEST_JSON.encodeToString(manifest))
        val manifestSha256 = sha256(manifestPath)
        val manifestAuditPersisted = store.recordBuildManifest(
            jobId = job.jobId,
            relativePath = "manifests/${job.jobId}/reprodroid-build.json",
            sha256 = manifestSha256,
        )
        if (!manifestAuditPersisted) {
            throw TrustedBuildFailure(
                "MANIFEST_AUDIT_PERSISTENCE_FAILED",
                "Runner could not persist the Build Environment Manifest audit record.",
            )
        }
        store.appendLog(job.jobId, LogLevel.INFO, "Build Environment Manifest recorded with SHA-256 $manifestSha256.")
        val storedArtifacts = mutableListOf<StoredArtifact>()
        try {
            artifactPaths.zip(discoveredArtifacts).forEach { (sourcePath, metadata) ->
                storedArtifacts += persistArtifact(job.jobId, sourcePath, metadata)
                apkImport?.checkDeadline()
            }
        } catch (failure: Throwable) {
            deleteStoredArtifacts(storedArtifacts)
            throw failure
        }
        discoveredArtifacts.forEach { artifact ->
            store.appendLog(job.jobId, LogLevel.INFO, "APK detected: ${artifact.fileName}, ${artifact.sizeBytes} bytes, SHA-256 ${artifact.sha256}.")
        }
        val registered = try {
            store.completeRealSuccessIfActive(job.jobId, storedArtifacts)
        } catch (failure: Throwable) {
            deleteStoredArtifacts(storedArtifacts)
            throw failure
        }
        if (!registered) {
            deleteStoredArtifacts(storedArtifacts)
            throw CancellationException("Job became terminal before APK artifacts were registered.")
        }
    }

    private suspend fun verifyCheckout(
        sourceDirectory: Path,
        workspace: Path,
        environment: Map<String, String>,
        resolvedCommit: String,
        requireClean: Boolean,
    ) {
        val actualCommit = captureChecked(
            command = listOf("git", "-C", sourceDirectory.toString(), "rev-parse", "HEAD"),
            workingDirectory = workspace,
            environment = environment,
            timeout = Duration.ofMinutes(1),
            failureCode = "CHECKOUT_VERIFICATION_FAILED",
        ).singleOrNull()?.trim()?.lowercase()
        if (actualCommit != resolvedCommit) {
            throw TrustedBuildFailure("CHECKOUT_COMMIT_MISMATCH", "Detached checkout does not match the confirmed commit.")
        }
        if (requireClean) {
            val changes = captureChecked(
                command = listOf(
                    "git", "-C", sourceDirectory.toString(), "status", "--porcelain=v1",
                    "--untracked-files=all", "--ignored=matching",
                ),
                workingDirectory = workspace,
                environment = environment,
                timeout = Duration.ofMinutes(1),
                failureCode = "SOURCE_SCAN_INVALID",
            )
            if (changes.isNotEmpty()) {
                throw TrustedBuildFailure("SOURCE_SCAN_INVALID", "The reviewed source checkout changed before build resume.")
            }
        }
    }

    private suspend fun runChecked(
        jobId: String,
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        timeout: Duration,
        failureCode: String,
        outputPrefix: String,
    ) {
        val result = try {
            processExecutor.execute(command, workingDirectory, environment, timeout) { line ->
                store.appendLog(jobId, LogLevel.INFO, "[$outputPrefix] $line")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ProcessTimeoutException) {
            throw TrustedBuildFailure("PROCESS_TIMEOUT", failure.message ?: "External process timed out.")
        } catch (failure: ProcessExecutionException) {
            logProcessFailure(outputPrefix, failure)
            val code = if (failure.stage == ProcessFailureStage.AUDIT_APPEND) {
                "BUILD_LOG_PERSISTENCE_FAILED"
            } else {
                failureCode
            }
            throw TrustedBuildFailure(code, "$outputPrefix could not be started or monitored.").also {
                it.initCause(failure)
            }
        } catch (failure: Throwable) {
            trustedBuildLogger.error(
                "External process failure for {} (stage=UNKNOWN, type={}).",
                outputPrefix,
                failure.javaClass.simpleName,
            )
            throw TrustedBuildFailure(failureCode, "$outputPrefix could not be started or monitored.").also {
                it.initCause(failure)
            }
        }
        if (result.exitCode != 0) throw TrustedBuildFailure(failureCode, "$outputPrefix exited with code ${result.exitCode}.")
    }

    private fun logProcessFailure(outputPrefix: String, failure: ProcessExecutionException) {
        val sqlFailure = generateSequence(failure.cause) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
        trustedBuildLogger.error(
            "External process failure for {} (stage={}, type={}, sqlState={}, vendorCode={}).",
            outputPrefix,
            failure.stage.name,
            failure.cause?.javaClass?.simpleName ?: "none",
            sqlFailure?.sqlState ?: "none",
            sqlFailure?.errorCode?.toString() ?: "none",
        )
    }

    private suspend fun captureChecked(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        timeout: Duration,
        failureCode: String,
    ): List<String> {
        val result = try {
            processExecutor.execute(command, workingDirectory, environment, timeout, captureOutput = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            throw TrustedBuildFailure(failureCode, "External verification command could not be started or monitored.")
        }
        if (result.exitCode != 0) throw TrustedBuildFailure(failureCode, "External verification command failed.")
        return result.outputLines
    }

    private fun transition(jobId: String, state: JobState, progress: Int, message: String) {
        if (!store.transitionIfActive(jobId, state, progress, message)) {
            throw kotlinx.coroutines.CancellationException("Job is no longer active.")
        }
    }

    private fun discoverArtifacts(buildRoot: Path, recipe: BuildRecipe): List<Path> {
        val matchers = recipe.artifactPatterns.map { pattern ->
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        }
        val matches = Files.walk(buildRoot).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.name.endsWith(".apk", ignoreCase = true) &&
                    matchers.any { it.matches(buildRoot.relativize(path)) }
            }.sorted().toList()
        }
        if (matches.isEmpty()) {
            throw TrustedBuildFailure("APK_NOT_FOUND", "The build completed but the fixed recipe found no APK.")
        }
        if (recipe.requireSingleApk && matches.size != 1) {
            throw TrustedBuildFailure("UNEXPECTED_APK_COUNT", "The recipe requires exactly one APK but found ${matches.size}.")
        }
        return matches
    }

    private fun persistArtifact(jobId: String, sourcePath: Path, metadata: ArtifactMetadata): StoredArtifact {
        val artifactDirectory = stateDirectory.resolve("artifacts").resolve(jobId).also(Path::createDirectories)
        val storedPath = confinedPath(artifactDirectory, "${metadata.artifactId}.apk")
        if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            throw TrustedBuildFailure(
                "ARTIFACT_PERSISTENCE_MISMATCH",
                "The APK changed before it was copied into Runner artifact storage.",
            )
        }
        try {
            Files.copy(sourcePath, storedPath, LinkOption.NOFOLLOW_LINKS)
            if (!Files.isRegularFile(storedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw TrustedBuildFailure(
                    "ARTIFACT_PERSISTENCE_MISMATCH",
                    "The APK changed while it was copied into Runner artifact storage.",
                )
            }
            val storedSize = Files.size(storedPath)
            val storedSha256 = sha256(storedPath)
            if (storedSize != metadata.sizeBytes || storedSha256 != metadata.sha256) {
                throw TrustedBuildFailure(
                    "ARTIFACT_PERSISTENCE_MISMATCH",
                    "The APK changed while it was copied into Runner artifact storage.",
                )
            }
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(storedPath) }
            throw failure
        }
        return StoredArtifact(
            metadata = metadata,
            contentRelativePath = "artifacts/$jobId/${metadata.artifactId}.apk",
        )
    }

    private fun deleteStoredArtifacts(storedArtifacts: Iterable<StoredArtifact>) {
        storedArtifacts.mapNotNull(StoredArtifact::contentRelativePath).forEach { relativePath ->
            runCatching { Files.deleteIfExists(confinedPath(stateDirectory, relativePath)) }
        }
    }

    private fun captureDependencies(gradleUserHome: Path): List<ManifestFile> {
        val modulesDirectory = gradleUserHome.resolve("caches/modules-2/files-2.1")
        if (!Files.isDirectory(modulesDirectory)) return emptyList()
        return Files.walk(modulesDirectory).use { paths ->
            paths.filter { it.isRegularFile() }
                .sorted()
                .map { path ->
                    ManifestFile(
                        path = modulesDirectory.relativize(path).toString().replace('\\', '/'),
                        sizeBytes = Files.size(path),
                        sha256 = sha256(path),
                    )
                }
                .toList()
        }
    }

    private fun resolveBuildJavaRuntime(recipe: BuildRecipe, managedJavaHome: Path? = null): BuildJavaRuntime {
        val configuredHome = managedJavaHome?.toString() ?: recipe.javaHomeEnvironmentVariable?.let { variable ->
            System.getenv(variable)?.takeIf(String::isNotBlank)
                ?: throw TrustedBuildFailure(
                    "BUILD_JAVA_HOME_MISSING",
                    "The ${recipe.id} recipe requires $variable to identify its fixed Java ${recipe.javaMajor} runtime.",
                )
        } ?: System.getProperty("java.home")
        val home = runCatching { Path.of(configuredHome).toRealPath() }.getOrNull()
            ?: throw TrustedBuildFailure("BUILD_JAVA_HOME_INVALID", "The recipe Java home is not an existing directory.")
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw TrustedBuildFailure("BUILD_JAVA_HOME_INVALID", "The recipe Java home is not a regular directory.")
        }
        val executable = home.resolve("bin/java")
        val releaseFile = home.resolve("release")
        if (
            !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isExecutable(executable) ||
            !Files.isRegularFile(releaseFile, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw TrustedBuildFailure("BUILD_JAVA_HOME_INVALID", "The recipe Java home has no verified java executable and release metadata.")
        }
        val release = Properties().apply {
            try {
                Files.newBufferedReader(releaseFile).use(::load)
            } catch (_: IOException) {
                throw TrustedBuildFailure("BUILD_JAVA_HOME_INVALID", "The recipe Java release metadata could not be read.")
            }
        }
        val version = release.getProperty("JAVA_VERSION")?.trim()?.trim('"')
            ?: throw TrustedBuildFailure("BUILD_JAVA_HOME_INVALID", "The recipe Java version is missing.")
        val major = version.substringBefore('.').toIntOrNull()
        if (major != recipe.javaMajor) {
            throw TrustedBuildFailure(
                "JAVA_VERSION_MISMATCH",
                "The recipe requires Java ${recipe.javaMajor}, but its configured Java home contains version $version.",
            )
        }
        return BuildJavaRuntime(
            home = home,
            executable = executable,
            version = version,
            vendor = release.getProperty("IMPLEMENTOR")?.trim()?.trim('"') ?: "Unknown",
        )
    }
}

/**
 * A single bounded APK output is unambiguous even when an upstream release workflow renames it
 * after Gradle. Multiple outputs remain fail-closed unless exactly one has the persisted name.
 * Unselected private staging copies are removed before any artifact metadata is accepted.
 */
internal fun selectGenericImportedApk(
    candidates: List<Pair<Path, ManifestFile>>,
    expectedFileName: String,
): List<Pair<Path, ManifestFile>> {
    val selected = when (candidates.size) {
        1 -> candidates.single()
        else -> candidates.singleOrNull { (_, manifest) ->
            manifest.path.substringAfterLast('/') == expectedFileName
        }
    }
    if (selected == null) {
        try {
            candidates.forEach { (path, _) -> Files.deleteIfExists(path) }
        } catch (_: Exception) {
            candidates.forEach { (path, _) -> runCatching { Files.deleteIfExists(path) } }
            throw TrustedBuildFailure("SANDBOX_OUTPUT_INVALID", "Unselected generic APK staging copies could not be removed.")
        }
        return emptyList()
    }
    try {
        candidates.filterNot { it === selected }.forEach { (path, _) -> Files.delete(path) }
    } catch (_: Exception) {
        candidates.forEach { (path, _) -> runCatching { Files.deleteIfExists(path) } }
        throw TrustedBuildFailure("SANDBOX_OUTPUT_INVALID", "Unselected generic APK staging copies could not be removed.")
    }
    return listOf(selected)
}

internal data class BuildJavaRuntime(
    val home: Path,
    val executable: Path,
    val version: String,
    val vendor: String,
)

@Serializable
internal data class BuildEnvironmentManifest(
    val schemaVersion: Int = 5,
    val generatedAt: String,
    val jobId: String,
    val recipeId: String,
    val repositoryUrl: String,
    val requestedRevision: RequestedRevision,
    val resolvedCommitSha: String,
    val buildRoot: String,
    val tasks: List<String>,
    val gradleVersion: String,
    val javaVersion: String,
    val javaVendor: String,
    val operatingSystem: String,
    val androidSdk: String?,
    val androidSdkApiLevel: Int,
    val buildToolsVersion: String,
    val determinism: DeterminismOptions? = null,
    val wrapper: WrapperVerification,
    val dependencies: List<ManifestFile>,
    val artifacts: List<ManifestFile>,
    val sandbox: SandboxEvidence? = null,
    val controllerJava: PublicJavaRuntime? = null,
    val controllerOperatingSystem: String? = null,
    val dockerAudit: DockerExecutionAudit? = null,
    val genericBuild: GenericBuildSnapshot? = null,
    val discovery: GenericDiscoveryEvidence? = null,
)

@Serializable
internal data class ManifestFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class ValidatedAndroidSdk(
    val apiLevel: Int,
    val buildToolsVersion: String,
)

internal fun validateAndroidSdkEnvironment(
    environment: Map<String, String>,
    recipe: BuildRecipe,
): ValidatedAndroidSdk {
    val configuredRoot = environment["ANDROID_SDK_ROOT"]
        ?: environment["ANDROID_HOME"]
        ?: throw TrustedBuildFailure(
            "ANDROID_SDK_ROOT_MISSING",
            "The fixed build recipe requires a configured Android SDK root.",
        )
    val sdkRoot = runCatching { Path.of(configuredRoot).toRealPath() }.getOrNull()
        ?: throw TrustedBuildFailure(
            "ANDROID_SDK_ROOT_INVALID",
            "The configured Android SDK root is not an existing directory.",
        )
    if (!Files.isDirectory(sdkRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw TrustedBuildFailure(
            "ANDROID_SDK_ROOT_INVALID",
            "The configured Android SDK root is not a non-symlink directory.",
        )
    }
    val platformDirectory = sdkRoot.resolve("platforms/${androidPlatformDirectoryName(recipe.androidSdkApiLevel)}").normalize()
    val androidJar = platformDirectory.resolve("android.jar").normalize()
    val buildToolsDirectory = sdkRoot.resolve("build-tools/${recipe.buildToolsVersion}").normalize()
    val aapt2 = buildToolsDirectory.resolve("aapt2").normalize()
    val platformRealPath = runCatching { platformDirectory.toRealPath() }.getOrNull()
    val androidJarRealPath = runCatching { androidJar.toRealPath() }.getOrNull()
    val buildToolsRealPath = runCatching { buildToolsDirectory.toRealPath() }.getOrNull()
    val aapt2RealPath = runCatching { aapt2.toRealPath() }.getOrNull()
    if (
        platformRealPath == null || androidJarRealPath == null ||
        !platformRealPath.startsWith(sdkRoot) || !androidJarRealPath.startsWith(platformRealPath) ||
        !Files.isDirectory(platformDirectory, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(androidJar, LinkOption.NOFOLLOW_LINKS)
    ) {
        throw TrustedBuildFailure(
            "ANDROID_SDK_PLATFORM_INVALID",
            "The recipe Android SDK platform package is missing or invalid.",
        )
    }
    if (
        buildToolsRealPath == null || aapt2RealPath == null ||
        !buildToolsRealPath.startsWith(sdkRoot) || !aapt2RealPath.startsWith(buildToolsRealPath) ||
        !Files.isDirectory(buildToolsDirectory, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(aapt2, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(aapt2)
    ) {
        throw TrustedBuildFailure(
            "ANDROID_BUILD_TOOLS_INVALID",
            "The recipe Android Build Tools package is missing or invalid.",
        )
    }
    return ValidatedAndroidSdk(recipe.androidSdkApiLevel, recipe.buildToolsVersion)
}

internal fun restrictedEnvironment(
    homeDirectory: Path,
    gradleUserHome: Path? = null,
    javaHome: Path = Path.of(System.getProperty("java.home")),
): Map<String, String> = buildMap {
    val inherited = System.getenv()
    listOf("PATH", "LANG", "LC_ALL", "TMPDIR", "ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
        inherited[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
    }
    put("HOME", homeDirectory.toString())
    put("JAVA_HOME", javaHome.toString())
    put("PATH", "${javaHome.resolve("bin")}:${get("PATH").orEmpty()}")
    put("GIT_LFS_SKIP_SMUDGE", "1")
    gradleUserHome?.let { put("GRADLE_USER_HOME", it.toString()) }
}

internal fun confinedPath(parent: Path, child: String): Path {
    val normalizedParent = parent.toAbsolutePath().normalize()
    val resolved = normalizedParent.resolve(child).normalize()
    if (!resolved.startsWith(normalizedParent)) {
        throw TrustedBuildFailure("UNSAFE_RECIPE_PATH", "A fixed recipe path escapes its workspace.")
    }
    return resolved
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val SHA256 = Regex("[0-9a-f]{64}")
private val MANIFEST_JSON = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}
