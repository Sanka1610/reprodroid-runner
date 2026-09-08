package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable

@Serializable
enum class ToolchainComponent { JDK, GRADLE, ANDROID_COMMAND_LINE_TOOLS, ANDROID_PLATFORM, ANDROID_BUILD_TOOLS, ANDROID_NDK, CMAKE }

@Serializable
enum class ToolchainArchiveType { ZIP, TAR_GZ }

@Serializable
enum class ToolchainSignaturePolicy { NONE, OPENPGP_REQUIRED }

@Serializable
data class AndroidLocalPackageMetadata(
    val path: String,
    val apiLevel: String,
    val revisionMajor: Int,
    val extensionLevel: Int,
    val baseExtension: Boolean,
    val codename: String,
    val layoutlibApi: Int,
    val displayName: String,
)

@Serializable
data class ToolchainCatalogArtifact(
    val artifactId: String,
    val component: ToolchainComponent,
    val version: String,
    val platform: String,
    val url: String,
    val archiveType: ToolchainArchiveType,
    val archiveSha256: String,
    val archiveSizeBytes: String,
    val maximumExpandedBytes: String,
    val maximumEntries: Int,
    val installSubdirectory: String,
    val licenseId: String,
    val signaturePolicy: ToolchainSignaturePolicy = ToolchainSignaturePolicy.NONE,
    val signatureUrl: String? = null,
    val signingKeyResource: String? = null,
    val signingKeyFingerprint: String? = null,
    val androidLocalPackage: AndroidLocalPackageMetadata? = null,
)

@Serializable
data class ToolchainLicense(
    val licenseId: String,
    val displayName: String,
    val text: String,
    val textSha256: String,
    val sourceUrl: String,
)

@Serializable
data class ToolchainCatalogResponse(
    val schemaVersion: Int,
    val catalogId: String,
    val catalogSha256: String,
    val generatedAt: String,
    val platform: String,
    val artifacts: List<ToolchainCatalogArtifact>,
    val licenses: List<ToolchainLicense>,
)

@Serializable
data class ToolchainRequirement(val component: ToolchainComponent, val version: String)

@Serializable
data class ResolveToolchainPlanRequest(val requirements: List<ToolchainRequirement>)

@Serializable
data class ToolchainPlanItem(
    val artifactId: String,
    val component: ToolchainComponent,
    val version: String,
    val archiveSha256: String,
    val downloadBytes: String,
    val reservedBytes: String,
    val alreadyInstalled: Boolean,
)

@Serializable
data class ToolchainPlanResponse(
    val schemaVersion: Int = 1,
    val runnerId: String,
    val catalogSha256: String,
    val planSha256: String,
    val platform: String,
    val items: List<ToolchainPlanItem>,
    val requiredLicenses: List<ToolchainLicense>,
    val downloadBytes: String,
    val reservedBytes: String,
)

@Serializable
data class ToolchainLicenseAcceptanceRequest(
    val licenseId: String,
    val licenseTextSha256: String,
    val accepted: Boolean,
)

@Serializable
data class CreateToolchainInstallationRequest(
    val planSha256: String,
    val catalogSha256: String,
    val requirements: List<ToolchainRequirement>,
    val licenseAcceptances: List<ToolchainLicenseAcceptanceRequest>,
)

@Serializable
enum class ToolchainInstallationState {
    PLANNED, AWAITING_LICENSE, RESERVING, DOWNLOADING, VERIFYING_ARCHIVE, EXTRACTING,
    VERIFYING_CONTENT, PUBLISHING, INSTALLED, CANCEL_REQUESTED, CANCELLED, FAILED,
    RECONCILIATION_REQUIRED,
}

@Serializable
data class ToolchainInstallationItemResponse(
    val artifactId: String,
    val component: ToolchainComponent,
    val version: String,
    val state: ToolchainInstallationState,
    val downloadedBytes: String,
)

@Serializable
data class ToolchainInstallationResponse(
    val schemaVersion: Int = 1,
    val installationId: String,
    val operationId: String,
    val runnerId: String,
    val planSha256: String,
    val catalogSha256: String,
    val state: ToolchainInstallationState,
    val progressPercent: Int,
    val items: List<ToolchainInstallationItemResponse>,
    val reason: V2PublicReason? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
enum class ToolchainInventoryState { VERIFIED, RECONCILIATION_REQUIRED }

@Serializable
data class ToolchainInventoryItem(
    val artifactId: String,
    val component: ToolchainComponent,
    val version: String,
    val archiveSha256: String,
    val contentManifestSha256: String,
    val installedBytes: String,
    val state: ToolchainInventoryState,
    val installedAt: String,
)

@Serializable
data class ToolchainInventoryResponse(
    val schemaVersion: Int = 1,
    val runnerId: String,
    val catalogSha256: String,
    val items: List<ToolchainInventoryItem>,
)

@Serializable
data class ToolchainRemovalRequest(val artifactIds: List<String>)

@Serializable
data class ToolchainRemovalPreviewResponse(
    val schemaVersion: Int = 1,
    val previewId: String,
    val artifactIds: List<String>,
    val releasableBytes: String,
    val expiresAt: String,
)

@Serializable
data class ExecuteToolchainRemovalRequest(val previewId: String)
