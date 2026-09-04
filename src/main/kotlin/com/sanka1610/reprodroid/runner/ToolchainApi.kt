package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun Route.toolchainV2Routes(store: ToolchainStore) {
    route("/v2/toolchains") {
        get("/catalog") { call.respond(store.catalog()) }
        get("/licenses/{licenseId}") {
            val licenseId = call.parameters["licenseId"]?.takeIf(String::isNotBlank)
                ?: throw ApiException.badRequest("LICENSE_ID_REQUIRED", "licenseId is required.")
            val catalog = store.catalog()
            call.respond(catalog.licenses.singleOrNull { it.licenseId == licenseId }
                ?: throw ApiException.notFound("LICENSE_NOT_FOUND", "The requested toolchain license does not exist."))
        }
        post("/plans:resolve") {
            call.respond(store.plan(call.receiveStrictToolchainRequest()))
        }
        get("/inventory") { call.respond(store.inventory()) }
        post("/installations") {
            val key = call.requireToolchainMutationHeaders()
            call.respond(HttpStatusCode.Accepted, store.create(call.receiveStrictToolchainRequest(), LOCAL_TOOLCHAIN_PRINCIPAL, key))
        }
        get("/installations/{installationId}") {
            call.respond(store.installation(call.requiredToolchainUuid("installationId")))
        }
        post("/installations/{installationId}:cancel") {
            call.requireToolchainMutationHeaders()
            call.respond(HttpStatusCode.Accepted, store.cancel(call.requiredToolchainUuid("installationId")))
        }
        post("/removals:preview") {
            val key = call.requireToolchainMutationHeaders()
            call.respond(store.removalPreview(call.receiveStrictToolchainRequest(), LOCAL_TOOLCHAIN_PRINCIPAL, key))
        }
        post("/removals:execute") {
            val key = call.requireToolchainMutationHeaders()
            call.respond(store.executeRemoval(call.receiveStrictToolchainRequest(), LOCAL_TOOLCHAIN_PRINCIPAL, key))
        }
    }
}

private fun ApplicationCall.requireToolchainMutationHeaders(): String {
    val contract = request.headers["X-ReproDroid-Contract"]
    if (contract != "toolchain-install@1") {
        throw ApiException.badRequest("CONTRACT_HEADER_REQUIRED", "X-ReproDroid-Contract must be toolchain-install@1.")
    }
    val idempotencyKey = request.headers["Idempotency-Key"]?.takeIf(String::isNotBlank)
        ?: throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.")
    return requireCanonicalUuid(idempotencyKey, "Idempotency-Key")
}

private val TOOLCHAIN_REQUEST_JSON = Json { explicitNulls = true; ignoreUnknownKeys = false }

private suspend inline fun <reified T> ApplicationCall.receiveStrictToolchainRequest(): T = try {
    TOOLCHAIN_REQUEST_JSON.decodeFromString(StrictV2Json.receive(this).toString())
} catch (failure: ApiException) {
    throw failure
} catch (_: SerializationException) {
    throw ApiException.badRequest("INVALID_REQUEST", "The request body does not match the toolchain contract.")
} catch (_: IllegalArgumentException) {
    throw ApiException.badRequest("INVALID_REQUEST", "The request body does not match the toolchain contract.")
}

private fun ApplicationCall.requiredToolchainUuid(name: String): String =
    parameters[name]?.let { requireCanonicalUuid(it, name) }
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name is required.")

private const val LOCAL_TOOLCHAIN_PRINCIPAL = "local-development"
