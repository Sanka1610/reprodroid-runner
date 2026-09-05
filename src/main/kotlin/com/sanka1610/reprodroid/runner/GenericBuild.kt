package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.erdtman.jcs.JsonCanonicalizer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

@Serializable
enum class GenericBuildAttempt { A, B }

@Serializable
data class GenericBuildConfiguration(
    val schemaVersion: Int,
    val buildRoot: String,
    val modulePath: String,
    val variant: String,
    val tasks: List<String>,
    val javaMajor: Int,
    val gradleVersion: String,
    val compileSdk: Int,
    val buildToolsVersion: String,
    val ndkVersion: String? = null,
    val cmakeVersion: String? = null,
)

@Serializable
data class GenericBuildSnapshot(
    val comparisonId: String,
    val attempt: GenericBuildAttempt,
    val configurationRevision: Long,
    val configurationSha256: String,
    val configurationCanonicalJson: String,
    val expectedArtifactFileName: String,
    val retryOfJobId: String? = null,
    val memoryBytes: Long = GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES,
)

@Serializable
data class GenericBuildResponse(
    val job: JobResponse,
    val genericBuild: GenericBuildSnapshot,
    val discovery: GenericDiscoveryEvidence? = null,
)

@Serializable
data class GenericDiscoveryEvidence(
    val schemaVersion: Int = 1,
    val jobId: String,
    val attempt: GenericBuildAttempt,
    val configurationSha256: String,
    val outputSha256: String,
    val outputBytes: Long,
    val selectedModule: String,
    val selectedVariant: String,
    val selectedTasks: List<String>,
    val observedAt: String,
)

@Serializable
enum class RawComparisonResult { MATCH, DIFFERENT, INCOMPARABLE }

@Serializable
data class OfficialApkIdentity(
    val sha256: String,
    val sizeBytes: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

@Serializable
data class CreateGenericComparisonRequest(
    val comparisonId: String,
    val configurationSha256: String,
    val officialIdentity: OfficialApkIdentity,
    val buildAJobId: String,
    val buildBJobId: String,
    val officialVsA: RawComparisonResult,
    val officialVsB: RawComparisonResult,
    val buildAVsB: RawComparisonResult,
    val trustEligible: Boolean,
    val installEligible: Boolean,
)

@Serializable
data class GenericComparisonResponse(
    val schemaVersion: Int = 1,
    val comparisonId: String,
    val configurationSha256: String,
    val officialIdentity: OfficialApkIdentity,
    val buildAJobId: String,
    val buildBJobId: String,
    val officialVsA: RawComparisonResult,
    val officialVsB: RawComparisonResult,
    val buildAVsB: RawComparisonResult,
    val trustEligible: Boolean,
    val installEligible: Boolean,
    val reproducible: Boolean,
    val retryOfComparisonId: String? = null,
    val resourceRetryCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class GenericResourceRetryResponse(
    val schemaVersion: Int = 1,
    val comparisonId: String,
    val retryOfComparisonId: String,
    val buildAJobId: String,
    val buildBJobId: String,
    val memoryBytes: Long,
)

internal data class ManagedToolchains(
    val javaHome: Path,
    val gradleHome: Path,
    val androidSdkRoot: Path,
)

internal object GenericBuildContract {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true; encodeDefaults = true }
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val commitPattern = Regex("[0-9a-f]{40}")
    private val modulePathPattern = Regex(":(?:[A-Za-z_][A-Za-z0-9_.-]{0,127}(?::[A-Za-z_][A-Za-z0-9_.-]{0,127})*)?")
    private val variantPattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    private val taskPattern = Regex(":[A-Za-z_][A-Za-z0-9_.-]{0,127}(?::[A-Za-z_][A-Za-z0-9_.-]{0,127})*")
    private val fileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._+() -]{0,254}[.]apk", RegexOption.IGNORE_CASE)
    private val supportedGradle = setOf("8.14.3", "9.6.1", "9.7.1")
    private val supportedSdk = mapOf(36 to "36.0.0", 37 to "37.0.0")

    fun validate(
        repositoryUrl: String,
        commitSha: String,
        snapshot: GenericBuildSnapshot,
    ): GenericBuildConfiguration {
        BuildRecipeRegistry.canonicalRepositoryKey(repositoryUrl)
        if (!commitPattern.matches(commitSha)) invalid("commitSha must be a lowercase full SHA-1 value.")
        requireCanonicalUuid(snapshot.comparisonId, "comparisonId")
        if (snapshot.configurationRevision <= 0) invalid("configurationRevision must be positive.")
        if (!sha256Pattern.matches(snapshot.configurationSha256)) invalid("configurationSha256 is invalid.")
        if (snapshot.configurationCanonicalJson.toByteArray().size > 64 * 1024) invalid("configurationCanonicalJson is too large.")
        if (!fileNamePattern.matches(snapshot.expectedArtifactFileName) || '/' in snapshot.expectedArtifactFileName || '\\' in snapshot.expectedArtifactFileName) {
            invalid("expectedArtifactFileName must be a confined APK base name.")
        }
        snapshot.retryOfJobId?.let { requireCanonicalUuid(it, "retryOfJobId") }
        if (snapshot.memoryBytes !in setOf(GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES, GenericDockerSandboxProfile.RETRY_MEMORY_BYTES)) {
            invalid("memoryBytes is not an allowed generic resource profile.")
        }
        val canonicalBytes = try {
            JsonCanonicalizer(snapshot.configurationCanonicalJson).encodedUTF8
        } catch (_: Exception) {
            invalid("configurationCanonicalJson is not valid canonical JSON.")
        }
        if (canonicalBytes.decodeToString() != snapshot.configurationCanonicalJson || hash(canonicalBytes) != snapshot.configurationSha256) {
            invalid("The configuration canonical JSON and SHA-256 do not agree.")
        }
        val configuration = try {
            json.decodeFromString<GenericBuildConfiguration>(snapshot.configurationCanonicalJson)
        } catch (_: Exception) {
            invalid("The generic build configuration is invalid.")
        }
        if (configuration.schemaVersion != 1) invalid("Unsupported generic build configuration schemaVersion.")
        validateRelativePath(configuration.buildRoot)
        if (!modulePathPattern.matches(configuration.modulePath)) invalid("modulePath is invalid.")
        if (!variantPattern.matches(configuration.variant)) invalid("variant is invalid.")
        if (configuration.tasks.isEmpty() || configuration.tasks.size > 32 || configuration.tasks.distinct().size != configuration.tasks.size) {
            invalid("tasks must contain 1..32 distinct Gradle task paths.")
        }
        val modulePrefix = if (configuration.modulePath == ":") ":" else "${configuration.modulePath}:"
        if (configuration.tasks.any { !taskPattern.matches(it) || !it.startsWith(modulePrefix) }) {
            invalid("Each task must be a structured task inside modulePath.")
        }
        if (configuration.javaMajor != 21) invalid("Only the catalog-managed Java 21 runtime is supported.")
        if (configuration.gradleVersion !in supportedGradle) invalid("The requested Gradle version is not in the trusted catalog.")
        if (supportedSdk[configuration.compileSdk] != configuration.buildToolsVersion) {
            invalid("The Android platform and Build Tools pair is not in the trusted catalog.")
        }
        if (configuration.ndkVersion != null || configuration.cmakeVersion != null) {
            invalid("The current catalog does not contain NDK or CMake artifacts.")
        }
        return configuration
    }

    fun recipe(repositoryUrl: String, commitSha: String, snapshot: GenericBuildSnapshot): BuildRecipe {
        val configuration = validate(repositoryUrl, commitSha, snapshot)
        val moduleDirectory = configuration.modulePath.removePrefix(":").replace(':', '/')
        val outputRoot = listOf(moduleDirectory, "build/outputs/apk").filter(String::isNotBlank).joinToString("/")
        return BuildRecipe(
            id = "generic-${snapshot.configurationSha256.take(16)}-${snapshot.attempt.name.lowercase()}",
            repositoryUrl = repositoryUrl,
            revision = RequestedRevision(RevisionType.COMMIT, commitSha),
            variantName = configuration.variant,
            buildRoot = configuration.buildRoot,
            javaMajor = configuration.javaMajor,
            gradleVersion = configuration.gradleVersion,
            androidSdkApiLevel = configuration.compileSdk,
            buildToolsVersion = configuration.buildToolsVersion,
            distributionType = "bin",
            wrapperJarGradleVersion = configuration.gradleVersion,
            tasks = configuration.tasks,
            artifactPatterns = listOf("$outputRoot/**/*.apk", "$outputRoot/*.apk"),
            requireSingleApk = false,
            timeout = Duration.ofMinutes(60),
            allowRunnerSuppliedDistributionChecksum = true,
            dependencyPinning = DependencyPinning.NONE,
            determinism = DeterminismOptions(sourceDateEpoch = null, noBuildCache = true, fixedLocale = FixedLocale.C_UTF_8),
            managedToolchains = true,
            discoveryTimeout = Duration.ofMinutes(15),
        )
    }

    fun canonicalConfiguration(configuration: GenericBuildConfiguration): String {
        val raw = buildJsonObject {
            put("schemaVersion", JsonPrimitive(configuration.schemaVersion))
            put("buildRoot", JsonPrimitive(configuration.buildRoot))
            put("modulePath", JsonPrimitive(configuration.modulePath))
            put("variant", JsonPrimitive(configuration.variant))
            put("tasks", kotlinx.serialization.json.JsonArray(configuration.tasks.map(::JsonPrimitive)))
            put("javaMajor", JsonPrimitive(configuration.javaMajor))
            put("gradleVersion", JsonPrimitive(configuration.gradleVersion))
            put("compileSdk", JsonPrimitive(configuration.compileSdk))
            put("buildToolsVersion", JsonPrimitive(configuration.buildToolsVersion))
            put("ndkVersion", configuration.ndkVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("cmakeVersion", configuration.cmakeVersion?.let(::JsonPrimitive) ?: JsonNull)
        }
        return JsonCanonicalizer(raw.toString()).encodedString
    }

    private fun validateRelativePath(value: String) {
        if (value == ".") return
        if (value.isBlank() || value.startsWith('/') || value.endsWith('/') || '\\' in value ||
            value.toByteArray().size > 1024 || value.split('/').any { it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) }
        ) invalid("buildRoot must be a confined relative path.")
    }

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun invalid(message: String): Nothing = throw ApiException.badRequest("INVALID_GENERIC_BUILD", message)
}

internal fun ManagedToolchains.validate(configuration: GenericBuildConfiguration): ManagedToolchains {
    fun directory(path: Path, label: String): Path {
        val normalized = path.toAbsolutePath().normalize()
        val real = runCatching { normalized.toRealPath() }.getOrNull()
            ?: throw TrustedBuildFailure("TOOLCHAIN_NOT_INSTALLED", "$label is not installed in the managed toolchain store.")
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || real != normalized) {
            throw TrustedBuildFailure("TOOLCHAIN_CONTENT_INVALID", "$label is not a confined managed directory.")
        }
        return real
    }
    directory(javaHome, "JDK ${configuration.javaMajor}")
    directory(gradleHome, "Gradle ${configuration.gradleVersion}")
    directory(androidSdkRoot, "Android SDK")
    return this
}
