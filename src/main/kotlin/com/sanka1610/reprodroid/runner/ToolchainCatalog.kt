package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.erdtman.jcs.JsonCanonicalizer
import java.security.MessageDigest

@Serializable
private data class ToolchainCatalogDocument(
    val schemaVersion: Int,
    val catalogId: String,
    val generatedAt: String,
    val platform: String,
    val artifacts: List<ToolchainCatalogArtifact>,
    val licenses: List<ToolchainLicense>,
)

internal class ToolchainCatalog private constructor(
    val response: ToolchainCatalogResponse,
) {
    private val artifacts = response.artifacts.associateBy(ToolchainCatalogArtifact::artifactId)
    private val licenses = response.licenses.associateBy(ToolchainLicense::licenseId)

    fun resolve(requirements: List<ToolchainRequirement>): List<ToolchainCatalogArtifact> {
        if (requirements.isEmpty() || requirements.size > 16) {
            throw ApiException.badRequest("INVALID_REQUEST", "requirements must contain 1..16 items.")
        }
        if (requirements.distinct().size != requirements.size) {
            throw ApiException.badRequest("INVALID_REQUEST", "requirements must not contain duplicates.")
        }
        return requirements.map { requirement ->
            response.artifacts.singleOrNull {
                it.component == requirement.component && it.version == requirement.version && it.platform == response.platform
            } ?: throw ApiException.badRequest(
                "TOOLCHAIN_UNSUPPORTED",
                "No trusted catalog artifact matches ${requirement.component}:${requirement.version} on ${response.platform}.",
            )
        }.distinctBy(ToolchainCatalogArtifact::artifactId)
    }

    fun artifact(artifactId: String): ToolchainCatalogArtifact = artifacts[artifactId]
        ?: throw ApiException.notFound("TOOLCHAIN_NOT_FOUND", "The requested catalog artifact does not exist.")

    fun license(licenseId: String): ToolchainLicense = licenses[licenseId]
        ?: throw ApiException.notFound("LICENSE_NOT_FOUND", "The requested toolchain license does not exist.")

    companion object {
        private val JSON = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }

        fun load(resourceName: String = "/toolchain-catalog-v1.json"): ToolchainCatalog {
            val bytes = requireNotNull(ToolchainCatalog::class.java.getResourceAsStream(resourceName)) {
                "Missing bundled toolchain catalog $resourceName"
            }.use { it.readBytes() }
            val root = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
            val document = JSON.decodeFromJsonElement(ToolchainCatalogDocument.serializer(), root)
            validate(document)
            val canonical = JsonCanonicalizer(root.toString()).encodedUTF8
            val sha = MessageDigest.getInstance("SHA-256").digest(canonical).toLowerHex()
            return ToolchainCatalog(
                ToolchainCatalogResponse(
                    document.schemaVersion,
                    document.catalogId,
                    sha,
                    document.generatedAt,
                    document.platform,
                    document.artifacts,
                    document.licenses,
                ),
            )
        }

        private fun validate(document: ToolchainCatalogDocument) {
            require(document.schemaVersion == 1)
            require(document.catalogId == "reprodroid-toolchains-v1")
            require(document.platform == "linux-x86_64")
            require(document.artifacts.isNotEmpty())
            require(document.artifacts.map { it.artifactId }.distinct().size == document.artifacts.size)
            require(document.licenses.map { it.licenseId }.distinct().size == document.licenses.size)
            val licenseIds = document.licenses.map { it.licenseId }.toSet()
            document.licenses.forEach {
                require(SHA256.matches(it.textSha256))
                require(sha256(it.text.encodeToByteArray()) == it.textSha256)
                require(it.sourceUrl.startsWith("https://"))
            }
            document.artifacts.forEach {
                require(ARTIFACT_ID.matches(it.artifactId))
                require(SHA256.matches(it.archiveSha256))
                require(it.url.startsWith("https://"))
                require(it.archiveSizeBytes.toLong() > 0)
                require(it.maximumExpandedBytes.toLong() >= it.archiveSizeBytes.toLong())
                require(it.maximumEntries in 1..100_000)
                require(it.licenseId in licenseIds)
                require(!it.installSubdirectory.startsWith('/') && ".." !in it.installSubdirectory.split('/'))
                if (it.signaturePolicy == ToolchainSignaturePolicy.OPENPGP_REQUIRED) {
                    require(it.signatureUrl?.startsWith("https://") == true)
                    require(!it.signingKeyResource.isNullOrBlank())
                    require(FINGERPRINT.matches(it.signingKeyFingerprint.orEmpty()))
                }
                val localPackage = it.androidLocalPackage
                if (localPackage != null) {
                    require(it.component == ToolchainComponent.ANDROID_PLATFORM)
                    require(ANDROID_PACKAGE_PATH.matches(localPackage.path))
                    require(localPackage.path == it.installSubdirectory.removePrefix("android/").replace('/', ';'))
                    require(ANDROID_API_LEVEL.matches(localPackage.apiLevel))
                    require(localPackage.revisionMajor in 1..9999)
                    require(localPackage.extensionLevel in 0..9999)
                    require(localPackage.codename.matches(ANDROID_CODENAME))
                    require(localPackage.layoutlibApi in 1..9999)
                    require(localPackage.displayName.length in 1..128 && localPackage.displayName.all { char -> char.code in 0x20..0x7e })
                }
            }
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FINGERPRINT = Regex("[0-9A-F]{40}")
        private val ARTIFACT_ID = Regex("[a-z0-9][a-z0-9._+-]{2,100}")
        private val ANDROID_PACKAGE_PATH = Regex("platforms;android-[1-9][0-9]{0,2}(?:[.][0-9]{1,3})?")
        private val ANDROID_API_LEVEL = Regex("[1-9][0-9]{0,2}(?:[.][0-9]{1,3})?")
        private val ANDROID_CODENAME = Regex("[A-Za-z0-9_.-]{0,64}")
    }
}

internal fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }
