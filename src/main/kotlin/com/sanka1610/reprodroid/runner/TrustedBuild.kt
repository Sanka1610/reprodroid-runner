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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal class TrustedBuildFailure(
    val code: String,
    override val message: String,
) : RuntimeException(message)

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
        validateDistributionUrl(distributionUrl, recipe)
        val officialDistribution = checksumSource.distributionChecksum(recipe.gradleVersion, recipe.distributionType)
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

    private fun validateDistributionUrl(distributionUrl: String, recipe: BuildRecipe) {
        val uri = runCatching { URI(distributionUrl) }.getOrNull()
            ?: throw TrustedBuildFailure("INVALID_DISTRIBUTION_URL", "distributionUrl is not a valid URI.")
        val expectedPath = "/distributions/gradle-${recipe.gradleVersion}-${recipe.distributionType}.zip"
        if (
            uri.scheme?.lowercase() != "https" ||
            uri.host?.lowercase() !in OFFICIAL_GRADLE_HOSTS ||
            uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null ||
            uri.path != expectedPath
        ) {
            throw TrustedBuildFailure("INVALID_DISTRIBUTION_URL", "distributionUrl does not match the fixed Gradle recipe.")
        }
    }

    private companion object {
        val OFFICIAL_GRADLE_HOSTS = setOf("services.gradle.org", "downloads.gradle.org")
    }
}

internal class TrustedBuildExecutor(
    private val store: SQLiteJobStore,
    private val processExecutor: ProcessExecutor,
    private val wrapperVerifier: WrapperVerifier,
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun execute(job: StoredJob, recipe: BuildRecipe) {
        val resolvedCommit = job.resolvedCommitSha
            ?: throw TrustedBuildFailure("RESOLVED_COMMIT_MISSING", "The confirmed job has no resolved commit.")
        val workspace = stateDirectory.resolve("workspaces").resolve(job.jobId).normalize()
        val sourceDirectory = confinedPath(workspace, "source")
        val homeDirectory = confinedPath(workspace, "home").also(Path::createDirectories)
        val gradleUserHome = confinedPath(workspace, "gradle-user-home").also(Path::createDirectories)
        workspace.createDirectories()
        if (Files.exists(sourceDirectory)) {
            throw TrustedBuildFailure("WORKSPACE_ALREADY_EXISTS", "The job workspace already contains a source checkout.")
        }
        val buildJava = resolveBuildJavaRuntime(recipe)
        val environment = restrictedEnvironment(homeDirectory, gradleUserHome, buildJava.home)

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
        store.appendLog(job.jobId, LogLevel.INFO, "Detached checkout verified at commit $resolvedCommit.")

        transition(job.jobId, JobState.VERIFYING_WRAPPER, 40, "Verifying Gradle distribution and Wrapper JAR checksums.")
        val buildRoot = confinedPath(sourceDirectory, recipe.buildRoot)
        if (!Files.isDirectory(buildRoot)) {
            throw TrustedBuildFailure("BUILD_ROOT_MISSING", "The fixed build root does not exist.")
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
        transition(job.jobId, JobState.BUILDING, 55, "Starting confirmed Gradle tasks: ${recipe.tasks.joinToString(" ")}.")
        val javaExecutable = buildJava.executable.toString()
        val wrapperJar = confinedPath(buildRoot, "gradle/wrapper/gradle-wrapper.jar")
        runChecked(
            jobId = job.jobId,
            command = listOf(
                javaExecutable,
                "-Dgradle.user.home=$gradleUserHome",
                "-classpath", wrapperJar.toString(),
                "org.gradle.wrapper.GradleWrapperMain",
                "--no-daemon", "--console=plain",
            ) + recipe.tasks,
            workingDirectory = buildRoot,
            environment = environment,
            timeout = recipe.timeout,
            failureCode = "GRADLE_BUILD_FAILED",
            outputPrefix = "gradle",
        )

        transition(job.jobId, JobState.DISCOVERING_ARTIFACTS, 90, "Discovering APKs using the fixed artifact recipe.")
        val artifactPaths = discoverArtifacts(buildRoot, recipe)
        val discoveredArtifacts = artifactPaths.map { artifactPath ->
            ArtifactMetadata(
                artifactId = UUID.randomUUID().toString(),
                fileName = artifactPath.name,
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
            javaVersion = buildJava.version,
            javaVendor = buildJava.vendor,
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            androidSdk = environment["ANDROID_SDK_ROOT"] ?: environment["ANDROID_HOME"],
            wrapper = wrapper,
            dependencies = captureDependencies(gradleUserHome),
            artifacts = artifactPaths.zip(discoveredArtifacts).map { (path, metadata) ->
                ManifestFile(buildRoot.relativize(path).toString().replace('\\', '/'), metadata.sizeBytes, metadata.sha256)
            },
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
        } catch (_: Throwable) {
            throw TrustedBuildFailure(failureCode, "$outputPrefix could not be started or monitored.")
        }
        if (result.exitCode != 0) throw TrustedBuildFailure(failureCode, "$outputPrefix exited with code ${result.exitCode}.")
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

    private fun resolveBuildJavaRuntime(recipe: BuildRecipe): BuildJavaRuntime {
        val configuredHome = recipe.javaHomeEnvironmentVariable?.let { variable ->
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

internal data class BuildJavaRuntime(
    val home: Path,
    val executable: Path,
    val version: String,
    val vendor: String,
)

@Serializable
internal data class BuildEnvironmentManifest(
    val schemaVersion: Int = 1,
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
    val wrapper: WrapperVerification,
    val dependencies: List<ManifestFile>,
    val artifacts: List<ManifestFile>,
)

@Serializable
internal data class ManifestFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

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
private val MANIFEST_JSON = Json { prettyPrint = true }
