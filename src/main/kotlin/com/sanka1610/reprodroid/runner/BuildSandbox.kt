package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
enum class BuildSandboxMode { HOST, DOCKER }

@Serializable
enum class SandboxOrigin { NEW_JOB, LEGACY_HOST }

@Serializable
enum class SandboxCleanupStatus { NOT_CREATED, PENDING, COMPLETE }

@Serializable
data class JobSandbox(
    val mode: BuildSandboxMode,
    val origin: SandboxOrigin,
    val profileId: String? = null,
    val cleanupStatus: SandboxCleanupStatus? = null,
) {
    internal fun validateState(state: JobState) {
        if (mode != BuildSandboxMode.DOCKER) return
        when (state) {
            JobState.CREATED, JobState.RESOLVING_SOURCE, JobState.AWAITING_CONFIRMATION, JobState.QUEUED,
            JobState.CLONING, JobState.SCANNING_SOURCE, JobState.AWAITING_SCAN_REVIEW -> require(cleanupStatus == SandboxCleanupStatus.NOT_CREATED)
            JobState.BUILDING -> require(cleanupStatus != SandboxCleanupStatus.NOT_CREATED)
            JobState.DISCOVERING_ARTIFACTS, JobState.SUCCEEDED -> require(cleanupStatus == SandboxCleanupStatus.COMPLETE)
            else -> Unit
        }
    }

    init {
        when (mode) {
            BuildSandboxMode.HOST -> require(profileId == null && cleanupStatus == null)
            BuildSandboxMode.DOCKER -> require(
                origin == SandboxOrigin.NEW_JOB && profileId == DockerSandboxProfile.ID && cleanupStatus != null,
            )
        }
    }
}

internal enum class SandboxManifestFormat { LEGACY_ALLOWED, REQUIRED_V4 }

/** All policy values are explicit in canonical JSON; changing them requires a new profile version. */
@Serializable
internal data class DockerSandboxProfile(
    val profileId: String = ID,
    val image: String = "ubuntu@$IMAGE_DIGEST",
    val platform: String = "linux/amd64",
    val dockerExecutable: String = "/usr/bin/docker",
    val endpoint: String = "unix:///var/run/docker.sock",
    val uid: Int = 1000,
    val gid: Int = 1000,
    val cpuCount: Int = 8,
    val cpuset: String = "0-7",
    val memoryBytes: Long = 8_589_934_592,
    val memorySwapBytes: Long = 8_589_934_592,
    val pids: Int = 1024,
    val tmpfsBytes: Long = 1_073_741_824,
    val networkMode: String = "BRIDGE",
    val readOnlyRoot: Boolean = true,
    val capDropAll: Boolean = true,
    val noNewPrivileges: Boolean = true,
    val seccomp: String = "DEFAULT",
    val sdkReadOnly: Boolean = true,
    val jdkReadOnly: Boolean = true,
    val dockerSocketMounted: Boolean = false,
    val jobDiskQuotaEnforced: Boolean = false,
    val minimumFreeDiskBytes: Long = 17_179_869_184,
    val home: String = "/home/ubuntu",
    val source: String = "/work/source",
    val gradleHome: String = "/work/gradle-home",
    val jdk: String = "/opt/jdk",
    val sdk: String = "/opt/android-sdk",
    val recipeId: String = "morpheapp-microg-re-6.1.4-default-release",
    val javaMajor: Int = 18,
    val gradleVersion: String = "8.14.3",
    val wrapperJarGradleVersion: String = "8.11.1",
    val androidSdkApiLevel: Int = 36,
    val buildToolsVersion: String = "36.0.0",
) {
    fun requireSupported(recipe: BuildRecipe) {
        val fixedRecipe = BuildRecipeRegistry.defaultRecipes.single { it.id == recipeId }
        if (recipe != fixedRecipe) {
            throw TrustedBuildFailure("SANDBOX_PROFILE_UNSUPPORTED", "The recipe is not supported by the saved sandbox profile.")
        }
    }

    companion object {
        const val ID = "docker-microg-v1"
        const val IMAGE_DIGEST = "sha256:1e0a86e57d247923571b75e0aaf48a1449cf8c543d51fb3e07a4a7d7bfa79316"
    }
}

internal data class SandboxSnapshot(
    val jobSandbox: JobSandbox,
    val canonicalProfile: String?,
    val profileSha256: String?,
    val manifestFormat: SandboxManifestFormat,
) {
    fun validatedProfile(): DockerSandboxProfile? {
        try {
            require(manifestFormat != SandboxManifestFormat.LEGACY_ALLOWED || jobSandbox.origin == SandboxOrigin.LEGACY_HOST)
            if (jobSandbox.mode == BuildSandboxMode.HOST) {
                require(canonicalProfile == null && profileSha256 == null)
                return null
            }
            requireNotNull(canonicalProfile)
            require(canonicalProfile.toByteArray(Charsets.UTF_8).size <= 8192)
            require(profileSha256 == hash(canonicalProfile))
            val decoded = SNAPSHOT_JSON.decodeFromString<DockerSandboxProfile>(canonicalProfile)
            require(decoded == DockerSandboxProfile())
            require(canonicalProfile == SNAPSHOT_JSON.encodeToString(decoded))
            return decoded
        } catch (_: Exception) {
            throw invalidSandboxSnapshot()
        }
    }

    companion object {
        fun newJob(mode: BuildSandboxMode): SandboxSnapshot {
            val canonical = if (mode == BuildSandboxMode.DOCKER) SNAPSHOT_JSON.encodeToString(DockerSandboxProfile()) else null
            return SandboxSnapshot(
                jobSandbox = JobSandbox(
                    mode, SandboxOrigin.NEW_JOB,
                    profileId = if (mode == BuildSandboxMode.DOCKER) DockerSandboxProfile.ID else null,
                    cleanupStatus = if (mode == BuildSandboxMode.DOCKER) SandboxCleanupStatus.NOT_CREATED else null,
                ),
                canonicalProfile = canonical,
                profileSha256 = canonical?.let(::hash),
                manifestFormat = SandboxManifestFormat.REQUIRED_V4,
            )
        }

        private val SNAPSHOT_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

internal fun invalidSandboxSnapshot() = TrustedBuildFailure(
    "SANDBOX_SNAPSHOT_INVALID", "The persisted sandbox snapshot is invalid; execution is blocked.",
)

@Serializable
data class SandboxLimits(
    val cpuCount: Int, val cpuset: String, val memoryBytes: Long, val memorySwapBytes: Long,
    val pids: Int, val tmpfsBytes: Long,
)

@Serializable
data class SandboxIsolation(
    val uid: Int, val gid: Int, val readOnlyRoot: Boolean, val capDropAll: Boolean,
    val noNewPrivileges: Boolean, val seccomp: String, val sdkReadOnly: Boolean,
    val jdkReadOnly: Boolean, val dockerSocketMounted: Boolean, val jobDiskQuotaEnforced: Boolean,
)

@Serializable
data class SandboxEvidence(
    val mode: BuildSandboxMode,
    val profileId: String? = null,
    val imageDigest: String? = null,
    val platform: String? = null,
    val engineVersion: String? = null,
    val networkMode: String? = null,
    val limits: SandboxLimits? = null,
    val isolation: SandboxIsolation? = null,
) {
    internal fun validate() {
        if (mode == BuildSandboxMode.HOST) {
            require(this == SandboxEvidence(BuildSandboxMode.HOST))
        } else {
            val profile = DockerSandboxProfile()
            require(profileId == profile.profileId && imageDigest == DockerSandboxProfile.IMAGE_DIGEST)
            require(platform == profile.platform && networkMode == profile.networkMode)
            val version = requireNotNull(engineVersion)
            require(version.isNotBlank() && version.toByteArray(Charsets.UTF_8).size <= 128 &&
                version.all { it.code in 0x21..0x7e && it != '/' && it != '\\' })
            require(limits == SandboxLimits(profile.cpuCount, profile.cpuset, profile.memoryBytes,
                profile.memorySwapBytes, profile.pids, profile.tmpfsBytes))
            require(isolation == SandboxIsolation(profile.uid, profile.gid, true, true, true, "DEFAULT", true, true, false, false))
        }
    }
}
