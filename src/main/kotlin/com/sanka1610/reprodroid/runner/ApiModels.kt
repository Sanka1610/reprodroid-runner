package com.sanka1610.reprodroid.runner

import kotlinx.serialization.SerialName
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
    SCANNING_SOURCE,
    AWAITING_SCAN_REVIEW,
    VERIFYING_WRAPPER,
    DISCOVERING_CONFIGURATION,
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
enum class DependencyPinning {
    NONE,
    LOCKFILE,
    LOCKFILE_OFFLINE,
}

@Serializable
enum class FixedLocale {
    @SerialName("C.UTF-8")
    C_UTF_8,
}

@Serializable
data class DeterminismOptions(
    val sourceDateEpoch: Long? = null,
    val noBuildCache: Boolean,
    val fixedLocale: FixedLocale? = null,
)

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
    val genericBuild: GenericBuildSnapshot? = null,
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
data class ContinueSourceScanRequest(
    val scanResultSha256: String,
    val riskAcknowledged: Boolean,
)

@Serializable
enum class SourceScanStatus {
    SCANNING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class SourceScanDetectorId {
    UNICODE_BIDI_CONTROL,
    UNICODE_INVISIBLE_FORMAT,
    UNICODE_NON_NFC,
    CANONICAL_PATH_COLLISION,
    TEXT_ENCODING_UNSUPPORTED,
    PROCESS_EXEC_API,
    DYNAMIC_NATIVE_LOAD_API,
    NETWORK_DOWNLOAD_COMMAND,
}

@Serializable
data class SourceScanSummaryResponse(
    val status: SourceScanStatus,
    val scannerVersion: String,
    val resultSha256: String? = null,
    val scannedFiles: Int? = null,
    val scannedBytes: Long? = null,
    val findingCount: Int? = null,
    val requiresReview: Boolean? = null,
    val reviewed: Boolean? = null,
)

@Serializable
data class SourceScanStatistics(
    val scannedFiles: Int,
    val scannedBytes: Long,
    val skippedBinaryFiles: Int,
    val skippedSymlinks: Int,
    val findingCount: Int,
)

@Serializable
data class SourceScanDetectorCount(
    val detectorId: SourceScanDetectorId,
    val count: Int,
)

@Serializable
data class SourceScanFinding(
    val detectorId: SourceScanDetectorId,
    val displayPath: String,
    val line: Int? = null,
    val column: Int? = null,
)

@Serializable
data class SourceScanDetailResponse(
    val schemaVersion: Int,
    val jobId: String,
    val resolvedCommitSha: String,
    val scannerVersion: String,
    val resultSha256: String,
    val summary: SourceScanStatistics,
    val detectorCounts: List<SourceScanDetectorCount>,
    val findings: List<SourceScanFinding>,
)

@Serializable
data class EffectiveBuild(
    val recipeId: String? = null,
    val variantName: String? = null,
    val buildRoot: String,
    val javaMajor: Int? = null,
    val tasks: List<String>,
    val dependencyPinning: DependencyPinning,
    val determinism: DeterminismOptions,
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
    val sourceScan: SourceScanSummaryResponse? = null,
    val latestLogSequence: Long,
    val artifacts: List<ArtifactMetadata>,
    val error: JobError? = null,
    val createdAt: String,
    val updatedAt: String,
    val sandbox: JobSandbox? = null,
    val genericBuild: GenericBuildSnapshot? = null,
    val discovery: GenericDiscoveryEvidence? = null,
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
    val determinism: DeterminismOptions? = null,
    val sandbox: SandboxEvidence? = null,
    val genericBuild: GenericBuildSnapshot? = null,
    val discoverySha256: String? = null,
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
