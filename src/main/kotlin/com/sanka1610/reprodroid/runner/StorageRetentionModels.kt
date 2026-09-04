package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class V2Capability(
    val id: String,
    val contractVersion: Int,
)

@Serializable
data class V2CapabilitiesResponse(
    val apiVersion: String,
    val foundationContractVersion: Int,
    val runnerId: String,
    val runnerVersion: String,
    val capabilities: List<V2Capability>,
)

@Serializable
enum class V2OperationState {
    RESERVED,
    APPLYING,
    COMPLETED,
    REJECTED,
    RECONCILIATION_REQUIRED,
}

@Serializable
data class V2OperationResult(
    val type: String,
    val resourceId: String,
)

@Serializable
data class V2PublicReason(
    val code: String,
    val message: String,
)

@Serializable
data class V2OperationResponse(
    val operationId: String,
    val state: V2OperationState,
    val kind: String,
    val requestSha256: String,
    val result: V2OperationResult? = null,
    val reason: V2PublicReason? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
enum class StorageArea {
    RUNNER_JOB,
    RUNNER_TOOLCHAIN,
}

@Serializable
enum class StorageAreaState {
    OK,
    WARNING,
    OVER_BUDGET,
    STORAGE_UNAVAILABLE,
}

@Serializable
enum class StorageMeasurementState {
    COMPLETE,
    INCOMPLETE,
    FAILED,
}

@Serializable
data class StorageAreaSummary(
    val area: StorageArea,
    val budgetBytes: String,
    val usedBytes: String,
    val reservedBytes: String,
    val unclassifiedBytes: String,
    val usableBytes: String,
    val warningPercent: Int,
    val state: StorageAreaState,
    val measurementState: StorageMeasurementState,
    val measuredAt: String,
)

@Serializable
data class StorageSummaryResponse(
    val schemaVersion: Int = 1,
    val runnerId: String,
    val areas: List<StorageAreaSummary>,
)

internal enum class RetentionResourceKind {
    JOB,
    ARTIFACT,
}

internal enum class CleanupResourceKind {
    JOB_WORKSPACE,
    JOB_ARTIFACT,
    JOB_MANIFEST,
    JOB_LOG,
    SANDBOX_IMPORT,
}

internal enum class CleanupRunState {
    PREVIEWED,
    APPLYING,
    COMPLETE,
    PARTIAL,
    REJECTED,
    RECONCILIATION_REQUIRED,
}

internal enum class CleanupItemResult {
    DELETED,
    ALREADY_MISSING,
    SKIPPED_PROTECTED,
    FAILED,
}

internal data class HoldCreateCommand(
    val resourceKind: RetentionResourceKind,
    val resourceId: String,
    val reason: String,
    val clientReferenceType: String,
    val clientReferenceId: String,
    val normalizedRequest: JsonObject,
)

internal data class ReservationCreateCommand(
    val area: StorageArea,
    val purpose: String,
    val resourceKind: RetentionResourceKind,
    val resourceId: String,
    val requestedBytes: Long,
    val normalizedRequest: JsonObject,
)

internal data class CleanupPreviewCommand(
    val area: StorageArea,
    val resourceKinds: Set<CleanupResourceKind>,
    val eligibleBefore: String,
    val resourceIds: Set<String>,
    val normalizedRequest: JsonObject,
)

internal data class CleanupExecuteCommand(
    val itemIds: List<String>,
    val normalizedRequest: JsonObject,
)

@Serializable
data class CleanupPreviewItemResponse(
    val itemId: String,
    val resourceKind: String,
    val resourceId: String,
    val observedBytes: String,
    val observedToken: String,
    val eligibleAt: String,
    val protectionReasons: List<String>,
)

@Serializable
data class CleanupPreviewResponse(
    val schemaVersion: Int = 1,
    val previewId: String,
    val runnerId: String,
    val state: String,
    val expiresAt: String,
    val truncated: Boolean,
    val items: List<CleanupPreviewItemResponse>,
)

@Serializable
data class CleanupItemResultResponse(
    val itemId: String,
    val result: String,
    val releasedBytes: String,
    val reason: V2PublicReason? = null,
)

@Serializable
data class CleanupRunResponse(
    val schemaVersion: Int = 1,
    val cleanupRunId: String,
    val previewId: String,
    val state: String,
    val releasedBytes: String,
    val items: List<CleanupItemResultResponse>,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)
