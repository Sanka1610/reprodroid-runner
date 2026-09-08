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
            JobState.VERIFYING_WRAPPER, JobState.DISCOVERING_CONFIGURATION, JobState.BUILDING -> Unit
            JobState.DISCOVERING_ARTIFACTS, JobState.SUCCEEDED -> require(cleanupStatus == SandboxCleanupStatus.COMPLETE)
            else -> Unit
        }
    }

    init {
        when (mode) {
            BuildSandboxMode.HOST -> require(profileId == null && cleanupStatus == null)
            BuildSandboxMode.DOCKER -> require(
                origin == SandboxOrigin.NEW_JOB && profileId in SUPPORTED_DOCKER_PROFILE_IDS && cleanupStatus != null,
            )
        }
    }
}

private val SUPPORTED_DOCKER_PROFILE_IDS = setOf(
    DockerSandboxProfile.ID,
    LegacyGenericDockerSandboxProfile.ID,
    GenericDockerSandboxProfile.ID,
)

internal enum class SandboxManifestFormat { LEGACY_ALLOWED, REQUIRED_V4 }

internal interface DockerSandboxPolicy {
    val profileId: String
    val image: String
    val platform: String
    val dockerExecutable: String
    val endpoint: String
    val uid: Int
    val gid: Int
    val cpuCount: Int
    val cpuset: String
    val memoryBytes: Long
    val memorySwapBytes: Long
    val pids: Int
    val tmpfsBytes: Long
    val networkMode: String
    val minimumFreeDiskBytes: Long
    val home: String
    val source: String
    val gradleHome: String
    val jdk: String
    val sdk: String
    val gradle: String?
    val gradleReadOnly: Boolean
}

/** All policy values are explicit in canonical JSON; changing them requires a new profile version. */
@Serializable
internal data class DockerSandboxProfile(
    override val profileId: String = ID,
    override val image: String = "ubuntu@$IMAGE_DIGEST",
    override val platform: String = "linux/amd64",
    override val dockerExecutable: String = "/usr/bin/docker",
    override val endpoint: String = "unix:///var/run/docker.sock",
    override val uid: Int = 1000,
    override val gid: Int = 1000,
    override val cpuCount: Int = 8,
    override val cpuset: String = "0-7",
    override val memoryBytes: Long = 8_589_934_592,
    override val memorySwapBytes: Long = 8_589_934_592,
    override val pids: Int = 1024,
    override val tmpfsBytes: Long = 1_073_741_824,
    override val networkMode: String = "BRIDGE",
    val readOnlyRoot: Boolean = true,
    val capDropAll: Boolean = true,
    val noNewPrivileges: Boolean = true,
    val seccomp: String = "DEFAULT",
    val sdkReadOnly: Boolean = true,
    val jdkReadOnly: Boolean = true,
    val dockerSocketMounted: Boolean = false,
    val jobDiskQuotaEnforced: Boolean = false,
    override val minimumFreeDiskBytes: Long = 17_179_869_184,
    override val home: String = "/home/ubuntu",
    override val source: String = "/work/source",
    override val gradleHome: String = "/work/gradle-home",
    override val jdk: String = "/opt/jdk",
    override val sdk: String = "/opt/android-sdk",
    val recipeId: String = "morpheapp-microg-re-6.1.4-default-release",
    val javaMajor: Int = 18,
    val gradleVersion: String = "8.14.3",
    val wrapperJarGradleVersion: String = "8.11.1",
    val androidSdkApiLevel: Int = 36,
    val buildToolsVersion: String = "36.0.0",
) : DockerSandboxPolicy {
    override val gradle: String? get() = null
    override val gradleReadOnly: Boolean get() = false

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

/** Historical Phase 4.4 profile. Its serialized shape and runtime policy remain immutable. */
@Serializable
internal data class LegacyGenericDockerSandboxProfile(
    override val profileId: String = ID,
    override val image: String = "ubuntu@${DockerSandboxProfile.IMAGE_DIGEST}",
    override val platform: String = "linux/amd64",
    override val dockerExecutable: String = "/usr/bin/docker",
    override val endpoint: String = "unix:///var/run/docker.sock",
    override val uid: Int = 1000,
    override val gid: Int = 1000,
    override val cpuCount: Int = 4,
    override val cpuset: String = "0-3",
    override val memoryBytes: Long = GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES,
    override val memorySwapBytes: Long = GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES,
    override val pids: Int = 1024,
    override val tmpfsBytes: Long = 1_073_741_824,
    override val networkMode: String = "BRIDGE",
    val readOnlyRoot: Boolean = true,
    val capDropAll: Boolean = true,
    val noNewPrivileges: Boolean = true,
    val seccomp: String = "DEFAULT",
    val sdkReadOnly: Boolean = true,
    val jdkReadOnly: Boolean = true,
    override val gradleReadOnly: Boolean = true,
    val dockerSocketMounted: Boolean = false,
    val jobDiskQuotaEnforced: Boolean = false,
    override val minimumFreeDiskBytes: Long = 17_179_869_184,
    override val home: String = "/home/ubuntu",
    override val source: String = "/work/source",
    override val gradleHome: String = "/work/gradle-home",
    override val jdk: String = "/opt/jdk",
    override val sdk: String = "/opt/android-sdk",
    override val gradle: String = "/opt/gradle",
    val maxGradleWorkers: Int = 2,
    val detailedLogBytes: Long = 67_108_864,
) : DockerSandboxPolicy {
    init {
        require(memoryBytes in setOf(GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES, GenericDockerSandboxProfile.RETRY_MEMORY_BYTES))
        require(memorySwapBytes == memoryBytes)
    }

    companion object { const val ID = "docker-generic-v1" }
}

/** Phase 4.7 generic profile with a bounded executable native-library tmpfs. */
@Serializable
internal data class GenericDockerSandboxProfile(
    override val profileId: String = ID,
    override val image: String = "ubuntu@${DockerSandboxProfile.IMAGE_DIGEST}",
    override val platform: String = "linux/amd64",
    override val dockerExecutable: String = "/usr/bin/docker",
    override val endpoint: String = "unix:///var/run/docker.sock",
    override val uid: Int = 1000,
    override val gid: Int = 1000,
    override val cpuCount: Int = 4,
    override val cpuset: String = "0-3",
    override val memoryBytes: Long = DEFAULT_MEMORY_BYTES,
    override val memorySwapBytes: Long = DEFAULT_MEMORY_BYTES,
    override val pids: Int = 1024,
    override val tmpfsBytes: Long = TOTAL_TMPFS_BYTES,
    val regularTmpfsBytes: Long = REGULAR_TMPFS_BYTES,
    val nativeTmpfsBytes: Long = NATIVE_TMPFS_BYTES,
    val nativeTmpfsPath: String = NATIVE_TMPFS_PATH,
    val sqliteNativeJvmOption: String = SQLITE_NATIVE_JVM_OPTION,
    override val networkMode: String = "BRIDGE",
    val readOnlyRoot: Boolean = true,
    val capDropAll: Boolean = true,
    val noNewPrivileges: Boolean = true,
    val seccomp: String = "DEFAULT",
    val sdkReadOnly: Boolean = true,
    val jdkReadOnly: Boolean = true,
    override val gradleReadOnly: Boolean = true,
    val dockerSocketMounted: Boolean = false,
    val jobDiskQuotaEnforced: Boolean = false,
    override val minimumFreeDiskBytes: Long = 17_179_869_184,
    override val home: String = "/home/ubuntu",
    override val source: String = "/work/source",
    override val gradleHome: String = "/work/gradle-home",
    override val jdk: String = "/opt/jdk",
    override val sdk: String = "/opt/android-sdk",
    override val gradle: String = "/opt/gradle",
    val maxGradleWorkers: Int = 2,
    val detailedLogBytes: Long = 67_108_864,
) : DockerSandboxPolicy {
    init {
        require(memoryBytes in setOf(DEFAULT_MEMORY_BYTES, RETRY_MEMORY_BYTES))
        require(memorySwapBytes == memoryBytes)
        require(tmpfsBytes == TOTAL_TMPFS_BYTES)
        require(regularTmpfsBytes == REGULAR_TMPFS_BYTES && nativeTmpfsBytes == NATIVE_TMPFS_BYTES)
        require(regularTmpfsBytes + nativeTmpfsBytes == tmpfsBytes)
        require(nativeTmpfsPath == NATIVE_TMPFS_PATH && sqliteNativeJvmOption == SQLITE_NATIVE_JVM_OPTION)
    }

    companion object {
        const val ID = "docker-generic-v2"
        const val DEFAULT_MEMORY_BYTES = 8_589_934_592L
        const val RETRY_MEMORY_BYTES = 12_884_901_888L
        const val TOTAL_TMPFS_BYTES = 1_073_741_824L
        const val REGULAR_TMPFS_BYTES = 1_006_632_960L
        const val NATIVE_TMPFS_BYTES = 67_108_864L
        const val NATIVE_TMPFS_PATH = "/run/reprodroid-native"
        const val SQLITE_NATIVE_JVM_OPTION = "-Dorg.sqlite.tmpdir=$NATIVE_TMPFS_PATH"
    }
}

internal fun DockerSandboxPolicy.containerEnvironment(): Map<String, String> = buildMap {
    put("PATH", listOfNotNull("$jdk/bin", gradle?.let { "$it/bin" }, "/usr/bin", "/bin").joinToString(":"))
    put("JAVA_HOME", jdk)
    put("HOME", home)
    put("GRADLE_USER_HOME", gradleHome)
    put("ANDROID_HOME", sdk)
    put("ANDROID_SDK_ROOT", sdk)
    if (this@containerEnvironment is GenericDockerSandboxProfile) {
        put("JAVA_TOOL_OPTIONS", sqliteNativeJvmOption)
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
            require(jobSandbox.profileId == DockerSandboxProfile.ID)
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

        fun newGenericJob(memoryBytes: Long = GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES): SandboxSnapshot {
            val profile = GenericDockerSandboxProfile(memoryBytes = memoryBytes, memorySwapBytes = memoryBytes)
            val canonical = SNAPSHOT_JSON.encodeToString(profile)
            return SandboxSnapshot(
                jobSandbox = JobSandbox(
                    BuildSandboxMode.DOCKER,
                    SandboxOrigin.NEW_JOB,
                    profileId = GenericDockerSandboxProfile.ID,
                    cleanupStatus = SandboxCleanupStatus.NOT_CREATED,
                ),
                canonicalProfile = canonical,
                profileSha256 = hash(canonical),
                manifestFormat = SandboxManifestFormat.REQUIRED_V4,
            )
        }

        private val SNAPSHOT_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private val GENERIC_SNAPSHOT_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun validatedGenericProfile(): DockerSandboxPolicy? {
        try {
            if (jobSandbox.profileId !in setOf(LegacyGenericDockerSandboxProfile.ID, GenericDockerSandboxProfile.ID)) return null
            require(jobSandbox.mode == BuildSandboxMode.DOCKER && manifestFormat == SandboxManifestFormat.REQUIRED_V4)
            val canonical = requireNotNull(canonicalProfile)
            require(canonical.toByteArray(Charsets.UTF_8).size <= 8192)
            require(profileSha256 == hash(canonical))
            val decoded: DockerSandboxPolicy = when (jobSandbox.profileId) {
                LegacyGenericDockerSandboxProfile.ID -> GENERIC_SNAPSHOT_JSON.decodeFromString<LegacyGenericDockerSandboxProfile>(canonical)
                GenericDockerSandboxProfile.ID -> GENERIC_SNAPSHOT_JSON.decodeFromString<GenericDockerSandboxProfile>(canonical)
                else -> error("unsupported generic profile")
            }
            require(decoded.profileId == jobSandbox.profileId)
            val encoded = when (decoded) {
                is LegacyGenericDockerSandboxProfile -> GENERIC_SNAPSHOT_JSON.encodeToString(decoded)
                is GenericDockerSandboxProfile -> GENERIC_SNAPSHOT_JSON.encodeToString(decoded)
                else -> error("unsupported generic profile")
            }
            require(canonical == encoded)
            return decoded
        } catch (_: Exception) {
            throw invalidSandboxSnapshot()
        }
    }

    fun validateAnyProfile() {
        if (jobSandbox.profileId in setOf(LegacyGenericDockerSandboxProfile.ID, GenericDockerSandboxProfile.ID)) {
            validatedGenericProfile()
        } else {
            validatedProfile()
        }
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
    val gradleReadOnly: Boolean = false,
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
            val fixed = profileId == DockerSandboxProfile.ID
            val profile = DockerSandboxProfile()
            val generic = GenericDockerSandboxProfile()
            require(profileId in SUPPORTED_DOCKER_PROFILE_IDS && imageDigest == DockerSandboxProfile.IMAGE_DIGEST)
            require(platform == profile.platform && networkMode == profile.networkMode)
            val version = requireNotNull(engineVersion)
            require(version.isNotBlank() && version.toByteArray(Charsets.UTF_8).size <= 128 &&
                version.all { it.code in 0x21..0x7e && it != '/' && it != '\\' })
            val expectedLimits = if (fixed) {
                SandboxLimits(profile.cpuCount, profile.cpuset, profile.memoryBytes, profile.memorySwapBytes, profile.pids, profile.tmpfsBytes)
            } else {
                val memory = requireNotNull(limits).memoryBytes
                require(memory in setOf(GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES, GenericDockerSandboxProfile.RETRY_MEMORY_BYTES))
                SandboxLimits(generic.cpuCount, generic.cpuset, memory, memory, generic.pids, generic.tmpfsBytes)
            }
            require(limits == expectedLimits)
            require(isolation == SandboxIsolation(profile.uid, profile.gid, true, true, true, "DEFAULT", true, true, false, false, !fixed))
        }
    }
}
