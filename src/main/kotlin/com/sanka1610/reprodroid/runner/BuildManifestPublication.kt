package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val job = store.getStoredJob(jobId) ?: throw ApiException.notFound()
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
        if (schemaVersion == null || schemaVersion == 1) throw redactedEmpty()
        if (schemaVersion != INTERNAL_MANIFEST_SCHEMA_VERSION) throw manifestInvalid()
        val manifest = try {
            PRIVATE_JSON.decodeFromString<BuildEnvironmentManifest>(privateJson)
        } catch (_: SerializationException) {
            throw manifestInvalid()
        } catch (_: IllegalArgumentException) {
            throw manifestInvalid()
        }
        val response = project(job, manifest)
        val responseBytes = PUBLIC_JSON.encodeToString(response).toByteArray(StandardCharsets.UTF_8).size
        if (responseBytes > limits.maxPublicResponseBytes) throw publicationLimitExceeded()
        return response
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
    ): BuildEnvironmentManifestResponse {
        val resolvedCommit = job.resolvedCommitSha
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
            !isSafePublicText(manifest.javaVendor)
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
            manifestArtifact.sha256 != storedArtifact.sha256
        ) {
            throw manifestInvalid()
        }
        return BuildEnvironmentManifestResponse(
            commit = manifest.resolvedCommitSha,
            java = PublicJavaRuntime(manifest.javaVersion, manifest.javaVendor),
            gradle = manifest.gradleVersion,
            androidSdk = manifest.androidSdkApiLevel,
            buildTools = manifest.buildToolsVersion,
            dependencies = dependencies,
            apkHash = manifestArtifact.sha256,
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
        HttpStatusCode.InternalServerError,
        "BUILD_MANIFEST_INVALID",
        "The stored build environment manifest failed integrity validation.",
    )

    private companion object {
        const val INTERNAL_MANIFEST_SCHEMA_VERSION = 2
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
