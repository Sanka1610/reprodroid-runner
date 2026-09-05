package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal class BuildManifestPublisher(
    private val store: SQLiteJobStore,
    private val stateDirectory: Path,
    private val limits: BuildManifestPublicationLimits = BuildManifestPublicationLimits(),
) {
    fun publicManifest(jobId: String): BuildEnvironmentManifestResponse {
        val job = try { store.getStoredJob(jobId) } catch (_: TrustedBuildFailure) { throw manifestInvalid() }
            ?: throw ApiException.notFound()
        if (job.executionMode != ExecutionMode.REAL_TRUSTED || job.state != JobState.SUCCEEDED) {
            throw manifestNotReady()
        }
        val audit = store.getBuildManifestAudit(jobId) ?: throw manifestNotReady()
        if (!LOWERCASE_SHA256.matches(audit.sha256)) throw manifestInvalid()
        val manifestPath = resolveManifestPath(jobId, audit.relativePath)
        val manifestBytes = readManifestBytes(manifestPath)
        if (sha256(manifestBytes) != audit.sha256) throw manifestInvalid()
        val privateJson = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(manifestBytes))
                .toString()
        }.getOrElse { throw manifestInvalid() }
        val schemaVersion = try {
            PRIVATE_JSON.parseToJsonElement(privateJson).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        } catch (_: SerializationException) {
            throw manifestInvalid()
        } catch (_: IllegalArgumentException) {
            throw manifestInvalid()
        }
        if (schemaVersion == null || schemaVersion == 1) {
            if (job.sandbox?.manifestFormat != SandboxManifestFormat.LEGACY_ALLOWED) throw manifestInvalid()
            throw redactedEmpty()
        }
        if (schemaVersion !in SUPPORTED_INTERNAL_MANIFEST_SCHEMA_VERSIONS) throw manifestInvalid()
        val manifest = try {
            PRIVATE_JSON.decodeFromString<BuildEnvironmentManifest>(privateJson)
        } catch (_: SerializationException) {
            throw manifestInvalid()
        } catch (_: IllegalArgumentException) {
            throw manifestInvalid()
        }
        if ((schemaVersion == 2 && manifest.determinism != null) || (schemaVersion >= 3 && manifest.determinism == null)) {
            throw manifestInvalid()
        }
        val snapshot = job.sandbox ?: throw manifestInvalid()
        if (schemaVersion < 4) {
            if (snapshot.manifestFormat != SandboxManifestFormat.LEGACY_ALLOWED ||
                snapshot.jobSandbox.origin != SandboxOrigin.LEGACY_HOST || manifest.sandbox != null ||
                manifest.controllerJava != null || manifest.controllerOperatingSystem != null || manifest.dockerAudit != null) throw manifestInvalid()
        } else {
            val sandbox = manifest.sandbox ?: throw manifestInvalid()
            try { sandbox.validate() } catch (_: Exception) { throw manifestInvalid() }
            if (sandbox.mode != snapshot.jobSandbox.mode || manifest.controllerJava == null ||
                manifest.controllerOperatingSystem.isNullOrBlank()) throw manifestInvalid()
            val sandboxJson = PRIVATE_JSON.parseToJsonElement(privateJson).jsonObject.getValue("sandbox").jsonObject
            if (sandbox.mode == BuildSandboxMode.HOST) {
                if (sandboxJson.keys != setOf("mode") || manifest.dockerAudit != null) throw manifestInvalid()
            } else {
                if (sandboxJson.keys != setOf("mode", "profileId", "imageDigest", "platform", "engineVersion", "networkMode", "limits", "isolation")) throw manifestInvalid()
                validateDockerAudit(job, manifest)
            }
        }
        val response = project(job, manifest, schemaVersion)
        val responseBytes = PUBLIC_JSON.encodeToString(response).toByteArray(StandardCharsets.UTF_8).size
        if (responseBytes > limits.maxPublicResponseBytes) throw publicationLimitExceeded()
        return response
    }

    private fun validateDockerAudit(job: StoredJob, manifest: BuildEnvironmentManifest) {
        try {
            val snapshot = requireNotNull(job.sandbox)
            val fixedProfile = if (job.genericBuild == null) snapshot.validatedProfile() else null
            val genericProfile = if (job.genericBuild != null) snapshot.validatedGenericProfile() else null
            val profile: DockerSandboxPolicy = fixedProfile ?: genericProfile ?: error("missing profile")
            require(snapshot.jobSandbox.cleanupStatus == SandboxCleanupStatus.COMPLETE)
            val recipe = job.genericBuild?.let { GenericBuildContract.recipe(job.repositoryUrl, job.revision.value, it) }
                ?: BuildRecipeRegistry.defaultRecipes.single { it.id == manifest.recipeId }
            require(manifest.gradleVersion == recipe.gradleVersion &&
                manifest.wrapper.wrapperJarGradleVersion == recipe.wrapperJarGradleVersion &&
                manifest.androidSdkApiLevel == recipe.androidSdkApiLevel && manifest.buildToolsVersion == recipe.buildToolsVersion)
            val audit = requireNotNull(manifest.dockerAudit)
            // This private field is the controller's verified SDK source, not the container target or public SDK API level.
            require(manifest.androidSdk == audit.mounts.single { it.destination == profile.sdk && it.readOnly }.source)
            require(audit.snapshotSha256 == snapshot.profileSha256 && audit.exitCode == 0 && !audit.oomKilled)
            require(audit.sandbox == manifest.sandbox && audit.java == PublicJavaRuntime(manifest.javaVersion, manifest.javaVendor))
            require(audit.operatingSystem == manifest.operatingSystem)
            val resources = store.sandboxResources().filter { it.jobId == job.jobId }
            require(resources.size == 2 && resources.all { it.removed && it.ownerId == audit.ownerId && it.engineId == audit.engineId && it.cleanupFailureCode == null && it.buildFailureCode == null })
            val build = resources.single { it.attemptId == audit.buildAttemptId && it.role == SandboxResourceRole.BUILD }
            val preflight = resources.single { it.attemptId == audit.preflightAttemptId && it.role == SandboxResourceRole.PREFLIGHT }
            require(build.containerId == audit.buildContainerId && preflight.containerId == audit.preflightContainerId)
            require(PRIVATE_JSON.decodeFromString<DockerExecutionAudit>(requireNotNull(build.observationJson)) == audit)
            require(preflight.observationJson == audit.preflightInspection)
            val engine = PRIVATE_JSON.parseToJsonElement(audit.engineInfo).jsonObject
            require(engine.string("ID") == audit.engineId && engine.string("ServerVersion") == audit.sandbox.engineVersion)
            require(engine.string("OSType") == "linux" && engine.string("Architecture") in setOf("x86_64", "amd64"))
            val image = PRIVATE_JSON.parseToJsonElement(audit.imageInspection).jsonObject
            require(image.string("Id") == audit.imageId && image.string("Os") == "linux" && image.string("Architecture") == "amd64")
            require(image.getValue("RepoDigests").jsonArray.any { it.jsonPrimitive.content == profile.image })
            val environment = mapOf(
                "PATH" to listOfNotNull("${profile.jdk}/bin", profile.gradle?.let { "$it/bin" }, "/usr/bin", "/bin").joinToString(":"),
                "JAVA_HOME" to profile.jdk, "HOME" to profile.home,
                "GRADLE_USER_HOME" to profile.gradleHome, "ANDROID_HOME" to profile.sdk, "ANDROID_SDK_ROOT" to profile.sdk,
            )
            fixedProfile?.requireSupported(recipe)
            val launcher = if (job.genericBuild == null) {
                listOf(
                    "${profile.jdk}/bin/java", "-Duser.home=${profile.home}", "-Dgradle.user.home=${profile.gradleHome}",
                    "-classpath", "${profile.source}/gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain",
                )
            } else {
                listOf(
                    "${profile.jdk}/bin/java", "-Duser.home=${profile.home}", "-Dgradle.user.home=${profile.gradleHome}",
                    "-classpath", "${requireNotNull(profile.gradle)}/lib/*:${profile.gradle}/lib/plugins/*", "org.gradle.launcher.GradleMain",
                )
            }
            val genericOptions = if (job.genericBuild != null) listOf("--rerun-tasks", "--no-configuration-cache", "--max-workers=2") else emptyList()
            val command = launcher + gradleOptions(recipe) + genericOptions + recipe.tasks
            val workingDirectory = if (recipe.buildRoot == ".") profile.source else "${profile.source}/${recipe.buildRoot}"
            DockerBuildSpec(profile, audit.mounts, environment, workingDirectory).validateInspection(
                PRIVATE_JSON.parseToJsonElement(audit.buildInspection).jsonObject, build, audit.imageId, command,
            )
        } catch (_: Exception) { throw manifestInvalid() }
    }

    private fun readManifestBytes(manifestPath: Path): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            Files.newInputStream(manifestPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > limits.maxPrivateManifestBytes) throw publicationLimitExceeded()
                    output.write(buffer, 0, read)
                }
            }
        } catch (failure: ApiException) {
            throw failure
        } catch (_: Throwable) {
            throw manifestInvalid()
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resolveManifestPath(jobId: String, relativePath: String): Path {
        val stateRoot = runCatching { stateDirectory.toRealPath() }.getOrElse { throw manifestInvalid() }
        val manifestRoot = stateRoot.resolve("manifests").resolve(jobId).normalize()
        val candidate = runCatching { stateRoot.resolve(relativePath).normalize() }.getOrElse { throw manifestInvalid() }
        if (!candidate.startsWith(manifestRoot) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw manifestInvalid()
        }
        val manifestRootReal = runCatching { manifestRoot.toRealPath() }.getOrElse { throw manifestInvalid() }
        val candidateReal = runCatching { candidate.toRealPath() }.getOrElse { throw manifestInvalid() }
        if (!manifestRootReal.startsWith(stateRoot) || !candidateReal.startsWith(manifestRootReal)) {
            throw manifestInvalid()
        }
        return candidate
    }

    private fun project(
        job: StoredJob,
        manifest: BuildEnvironmentManifest,
        internalSchemaVersion: Int,
    ): BuildEnvironmentManifestResponse {
        val resolvedCommit = job.resolvedCommitSha
        val effectiveBuild = store.getJob(job.jobId)?.effectiveBuild
        val effectiveDeterminism = effectiveBuild?.determinism
        val unconfiguredDeterminism = DeterminismOptions(noBuildCache = false)
        if (
            !LOWERCASE_COMMIT_SHA.matches(manifest.resolvedCommitSha) ||
            manifest.resolvedCommitSha != resolvedCommit ||
            manifest.jobId != job.jobId ||
            manifest.requestedRevision != job.revision ||
            runCatching { BuildRecipeRegistry.canonicalRepositoryKey(manifest.repositoryUrl) }.getOrNull() !=
            runCatching { BuildRecipeRegistry.canonicalRepositoryKey(job.repositoryUrl) }.getOrNull() ||
            manifest.gradleVersion != manifest.wrapper.gradleVersion ||
            !VERSION_VALUE.matches(manifest.gradleVersion) ||
            manifest.androidSdkApiLevel !in 1..999 ||
            !VERSION_VALUE.matches(manifest.buildToolsVersion) ||
            !isSafePublicText(manifest.javaVersion) ||
            !isSafePublicText(manifest.javaVendor) ||
            (internalSchemaVersion == 2 && effectiveDeterminism != unconfiguredDeterminism) ||
            (internalSchemaVersion >= 3 && manifest.determinism != effectiveDeterminism) ||
            (internalSchemaVersion >= 4 && (effectiveBuild == null || manifest.recipeId != effectiveBuild.recipeId ||
                manifest.buildRoot != effectiveBuild.buildRoot || manifest.tasks != effectiveBuild.tasks ||
                manifest.javaVersion.substringBefore('.').toIntOrNull() != effectiveBuild.javaMajor)) ||
            (internalSchemaVersion >= 5 && (manifest.genericBuild != job.genericBuild ||
                manifest.discovery != store.genericDiscovery(job.jobId) ||
                (job.genericBuild != null && manifest.discovery == null)))
        ) {
            throw manifestInvalid()
        }
        if (manifest.dependencies.size > limits.maxPublicDependencies) throw publicationLimitExceeded()
        val dependencies = manifest.dependencies.map { dependency ->
            if (!isConfinedInternalRelativePath(dependency.path) || !LOWERCASE_SHA256.matches(dependency.sha256)) {
                throw manifestInvalid()
            }
            val fileName = dependency.path.substringAfterLast('/')
            if (fileName.toByteArray(StandardCharsets.UTF_8).size > limits.maxDependencyFileNameBytes) {
                throw publicationLimitExceeded()
            }
            if (!isSafeDependencyFileName(fileName)) throw redactionRequired()
            PublicBuildDependency(fileName, dependency.sha256)
        }.sortedWith(compareBy(PublicBuildDependency::fileName, PublicBuildDependency::sha256))
        val manifestArtifact = manifest.artifacts.singleOrNull() ?: throw manifestInvalid()
        val storedArtifact = store.getJob(job.jobId)?.artifacts?.singleOrNull() ?: throw manifestInvalid()
        if (
            !isConfinedInternalRelativePath(manifestArtifact.path) ||
            !LOWERCASE_SHA256.matches(manifestArtifact.sha256) ||
            manifestArtifact.sha256 != storedArtifact.sha256 ||
            (internalSchemaVersion >= 4 && manifestArtifact.sizeBytes != storedArtifact.sizeBytes)
        ) {
            throw manifestInvalid()
        }
        return BuildEnvironmentManifestResponse(
            schemaVersion = internalSchemaVersion - 1,
            commit = manifest.resolvedCommitSha,
            java = PublicJavaRuntime(manifest.javaVersion, manifest.javaVendor),
            gradle = manifest.gradleVersion,
            androidSdk = manifest.androidSdkApiLevel,
            buildTools = manifest.buildToolsVersion,
            dependencies = dependencies,
            apkHash = manifestArtifact.sha256,
            determinism = manifest.determinism,
            sandbox = manifest.sandbox,
            genericBuild = manifest.genericBuild,
            discoverySha256 = manifest.discovery?.outputSha256,
        )
    }

    private fun isSafeDependencyFileName(fileName: String): Boolean {
        if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) return false
        if (fileName.any(::isUnsafePublicCharacter)) return false
        val lowercase = fileName.lowercase()
        if (SENSITIVE_FILE_NAME_MARKER.containsMatchIn(lowercase)) return false
        return !KNOWN_TOKEN_PREFIX.containsMatchIn(fileName)
    }

    private fun isSafePublicText(value: String): Boolean =
        value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= MAX_PUBLIC_TEXT_BYTES &&
            value.none(::isUnsafePublicCharacter)

    private fun isUnsafePublicCharacter(character: Char): Boolean =
        character == '\u0000' || Character.isISOControl(character) ||
            Character.getType(character) == Character.FORMAT.toInt() ||
            Character.getType(character) == Character.SURROGATE.toInt()

    private fun isConfinedInternalRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.any { it == '\u0000' }) return false
        val segments = path.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." }
    }

    private fun manifestNotReady() = ApiException(
        HttpStatusCode.Conflict,
        "BUILD_MANIFEST_NOT_READY",
        "The build environment manifest is not available for this job.",
    )

    private fun publicationLimitExceeded() = ApiException(
        HttpStatusCode.Conflict,
        "BUILD_MANIFEST_PUBLICATION_LIMIT_EXCEEDED",
        "The build environment manifest exceeds the public API limits.",
    )

    private fun redactedEmpty() = ApiException(
        HttpStatusCode.Gone,
        "BUILD_MANIFEST_REDACTED_EMPTY",
        "The build environment manifest cannot produce the required public projection.",
    )

    private fun redactionRequired() = ApiException(
        HttpStatusCode.Gone,
        "BUILD_MANIFEST_REDACTION_REQUIRED",
        "The build environment manifest cannot be published without omitting dependency evidence.",
    )

    private fun manifestInvalid() = ApiException(
        HttpStatusCode.Conflict,
        "BUILD_MANIFEST_INVALID",
        "The stored build environment manifest failed integrity validation.",
    )

    private companion object {
        val SUPPORTED_INTERNAL_MANIFEST_SCHEMA_VERSIONS = setOf(2, 3, 4, 5)
        const val MAX_PUBLIC_TEXT_BYTES = 255
        val LOWERCASE_COMMIT_SHA = Regex("[0-9a-f]{40}")
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
        val VERSION_VALUE = Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?")
        val SENSITIVE_FILE_NAME_MARKER = Regex(
            "(?:^|[._-])(?:password|passwd|secret|credential|api[-_]?key)(?:[._-]|$)",
            RegexOption.IGNORE_CASE,
        )
        val KNOWN_TOKEN_PREFIX = Regex(
            "(?:gh[pousr]_|github_pat_|glpat-|AKIA|ASIA|xox[baprs]-|sk-(?:live|test|proj)-|ya29\\.)",
            RegexOption.IGNORE_CASE,
        )
        val PRIVATE_JSON = Json { ignoreUnknownKeys = false }
        val PUBLIC_JSON = Json { explicitNulls = false }
    }
}

internal data class BuildManifestPublicationLimits(
    val maxPrivateManifestBytes: Long = 32L * 1024 * 1024,
    val maxPublicResponseBytes: Int = 8 * 1024 * 1024,
    val maxPublicDependencies: Int = 20_000,
    val maxDependencyFileNameBytes: Int = 255,
)
