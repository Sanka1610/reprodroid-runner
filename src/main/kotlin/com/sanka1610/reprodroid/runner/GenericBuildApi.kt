package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.erdtman.jcs.JsonCanonicalizer
import java.security.MessageDigest

@Serializable
private data class GenericBuildCreateBody(
    val repositoryUrl: String,
    val commitSha: String,
    val genericBuild: GenericBuildSnapshot,
    val riskAcknowledged: Boolean,
)

internal fun Route.genericBuildV2Routes(coordinator: JobCoordinator, store: SQLiteJobStore, config: RunnerConfig, security: RunnerSecurityStore) {
    route("/v2") {
        post("/builds") {
            val principalId = call.requirePrincipal(config, security)
            val key = call.requireGenericMutationHeaders(GENERIC_BUILD_CONTRACT)
            val bodyObject = StrictV2Json.receive(call)
            val body = decode<GenericBuildCreateBody>(bodyObject.toString())
            if (!body.riskAcknowledged) {
                throw ApiException.forbidden("RISK_ACKNOWLEDGEMENT_REQUIRED", "Explicit acknowledgement of Gradle arbitrary-code execution is required.")
            }
            if (body.genericBuild.retryOfJobId != null || body.genericBuild.memoryBytes != GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES) {
                throw ApiException.badRequest("INVALID_GENERIC_BUILD", "Resource retry fields cannot be supplied to POST /v2/builds.")
            }
            val receipt = coordinator.createGeneric(
                body.repositoryUrl,
                body.commitSha,
                body.genericBuild,
                key,
                canonicalSha(bodyObject.toString()),
                principalId,
            )
            call.respond(if (receipt.existing) HttpStatusCode.OK else HttpStatusCode.Accepted, receipt.response)
        }
        get("/builds/{jobId}") {
            val principalId = call.requirePrincipal(config, security)
            val response = coordinator.get(call.requiredGenericUuid("jobId"), principalId)
            val generic = response.genericBuild
                ?: throw ApiException.notFound("GENERIC_BUILD_NOT_FOUND", "The requested Job is not a generic build.")
            call.respond(GenericBuildResponse(response, generic, response.discovery))
        }
        post("/builds/{jobId}:confirm") {
            val principalId = call.requirePrincipal(config, security)
            call.requireGenericMutationHeaders(GENERIC_BUILD_CONTRACT)
            val body = decode<ConfirmJobRequest>(StrictV2Json.receive(call).toString())
            val jobId = call.requiredGenericUuid("jobId")
            if (coordinator.get(jobId, principalId).genericBuild == null) throw ApiException.notFound("GENERIC_BUILD_NOT_FOUND", "The requested Job is not a generic build.")
            coordinator.confirm(jobId, body, principalId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/builds/{jobId}:scan-continue") {
            val principalId = call.requirePrincipal(config, security)
            call.requireGenericMutationHeaders(GENERIC_BUILD_CONTRACT)
            val body = decode<ContinueSourceScanRequest>(StrictV2Json.receive(call).toString())
            val jobId = call.requiredGenericUuid("jobId")
            if (coordinator.get(jobId, principalId).genericBuild == null) throw ApiException.notFound("GENERIC_BUILD_NOT_FOUND", "The requested Job is not a generic build.")
            coordinator.continueSourceScan(jobId, body, principalId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/builds/{jobId}:cancel") {
            val principalId = call.requirePrincipal(config, security)
            call.requireGenericMutationHeaders(GENERIC_BUILD_CONTRACT)
            val body = StrictV2Json.receive(call)
            if (body.isNotEmpty()) throw ApiException.badRequest("INVALID_REQUEST", "The cancel body must be an empty JSON object.")
            val jobId = call.requiredGenericUuid("jobId")
            if (coordinator.get(jobId, principalId).genericBuild == null) throw ApiException.notFound("GENERIC_BUILD_NOT_FOUND", "The requested Job is not a generic build.")
            coordinator.cancel(jobId, principalId)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/comparisons") {
            val principalId = call.requirePrincipal(config, security)
            val key = call.requireGenericMutationHeaders(APK_COMPARISON_CONTRACT)
            val bodyObject = StrictV2Json.receive(call)
            val request = decode<CreateGenericComparisonRequest>(bodyObject.toString())
            val receipt = store.createGenericComparison(request, key, canonicalSha(bodyObject.toString()), principalId)
            call.respond(if (receipt.existing) HttpStatusCode.OK else HttpStatusCode.Accepted, receipt.response)
        }
        get("/comparisons/{comparisonId}") {
            val principalId = call.requirePrincipal(config, security)
            call.respond(store.genericComparison(call.requiredGenericUuid("comparisonId"), principalId))
        }
        post("/comparisons/{comparisonId}:retry-resource") {
            val principalId = call.requirePrincipal(config, security)
            val key = call.requireGenericMutationHeaders(APK_COMPARISON_CONTRACT)
            val body = StrictV2Json.receive(call)
            if (body.isNotEmpty()) throw ApiException.badRequest("INVALID_REQUEST", "The retry body must be an empty JSON object.")
            val comparisonId = call.requiredGenericUuid("comparisonId")
            val receipt = coordinator.retryGenericResources(comparisonId, key, canonicalSha(body.toString()), principalId)
            call.respond(if (receipt.existing) HttpStatusCode.OK else HttpStatusCode.Accepted, receipt.response)
        }
    }
}

private fun ApplicationCall.requireGenericMutationHeaders(expectedContract: String): String {
    val contract = request.headers["X-ReproDroid-Contract"]
        ?: throw ApiException.badRequest("CONTRACT_REQUIRED", "X-ReproDroid-Contract is required.")
    if (contract != expectedContract) throw ApiException.conflict("CONTRACT_MISMATCH", "The requested operation contract is not supported.")
    val key = request.headers["Idempotency-Key"]
        ?: throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.")
    return requireCanonicalUuid(key, "Idempotency-Key")
}

private fun ApplicationCall.requiredGenericUuid(name: String): String =
    parameters[name]?.let { requireCanonicalUuid(it, name) }
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name is required.")

private inline fun <reified T> decode(text: String): T = try {
    GENERIC_API_JSON.decodeFromString(text)
} catch (_: Exception) {
    throw ApiException.badRequest("INVALID_REQUEST", "The request contains missing, unknown, or invalid fields.")
}

private fun canonicalSha(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(JsonCanonicalizer(text).encodedUTF8).joinToString("") { "%02x".format(it) }

private const val GENERIC_BUILD_CONTRACT = "generic-build@1"
private const val APK_COMPARISON_CONTRACT = "apk-comparison@1"
private val GENERIC_API_JSON = Json { ignoreUnknownKeys = false; explicitNulls = true; encodeDefaults = true }
