package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionMode {
    SIMULATED,
    REAL_TRUSTED,
}

@Serializable
enum class RevisionType {
    BRANCH,
    TAG,
    COMMIT,
}

@Serializable
enum class SimulationOutcome {
    SUCCESS,
    FAILURE,
}

@Serializable
enum class JobState {
    CREATED,
    RESOLVING_SOURCE,
    AWAITING_CONFIRMATION,
    QUEUED,
    CLONING,
    VERIFYING_WRAPPER,
    BUILDING,
    DISCOVERING_ARTIFACTS,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    ;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, CANCELLED, INTERRUPTED)
}

@Serializable
enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}

@Serializable
data class RequestedRevision(
    val type: RevisionType,
    val value: String,
)

@Serializable
data class CreateJobRequest(
    val executionMode: ExecutionMode,
    val repositoryUrl: String,
    val revision: RequestedRevision,
    val simulationOutcome: SimulationOutcome? = null,
)

@Serializable
data class CreateJobResponse(
    val jobId: String,
    val state: JobState,
)

@Serializable
data class ConfirmJobRequest(
    val resolvedCommitSha: String,
    val riskAcknowledged: Boolean,
)

@Serializable
data class EffectiveBuild(
    val recipeId: String? = null,
    val variantName: String? = null,
    val buildRoot: String,
    val javaMajor: Int? = null,
    val tasks: List<String>,
)

@Serializable
data class JobError(
    val code: String,
    val message: String,
)

@Serializable
data class ArtifactMetadata(
    val artifactId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

@Serializable
data class JobResponse(
    val jobId: String,
    val executionMode: ExecutionMode,
    val repositoryUrl: String,
    val requestedRevision: RequestedRevision,
    val resolvedCommitSha: String? = null,
    val state: JobState,
    val progressPercent: Int,
    val requiresConfirmation: Boolean,
    val effectiveBuild: EffectiveBuild? = null,
    val latestLogSequence: Long,
    val artifacts: List<ArtifactMetadata>,
    val error: JobError? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class LogEntry(
    val sequence: Long,
    val timestamp: String,
    val level: LogLevel,
    val message: String,
)

@Serializable
data class LogResponse(
    val entries: List<LogEntry>,
    val nextAfterSequence: Long,
    val hasMore: Boolean,
)

@Serializable
data class ArtifactListResponse(
    val artifacts: List<ArtifactMetadata>,
)

@Serializable
data class PublicJavaRuntime(
    val version: String,
    val vendor: String,
)

@Serializable
data class PublicBuildDependency(
    val fileName: String,
    val sha256: String,
)

@Serializable
data class BuildEnvironmentManifestResponse(
    val schemaVersion: Int,
    val commit: String,
    val java: PublicJavaRuntime,
    val gradle: String,
    val androidSdk: Int,
    val buildTools: String,
    val dependencies: List<PublicBuildDependency>,
    val apkHash: String,
)

@Serializable
data class HealthResponse(
    val runnerVersion: String,
    val apiVersion: String,
    val realBuildEnabled: Boolean,
    val databaseReady: Boolean,
)

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)
