package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun Route.pairingRoutes(store: RunnerSecurityStore) {
    route("/pairing/v1") {
        get("/identity") { call.respond(PairingIdentityResponse(runnerId = store.runnerId())) }
        post("/requests") {
            store.requireMutationAllowed(call.request.origin.remoteHost)
            val request = call.receivePairing<PairingCreateRequest>()
            call.respond(HttpStatusCode.Accepted, store.createRequest(request, call.request.origin.remoteHost))
        }
        get("/requests/{requestId}") {
            call.respond(store.status(call.requiredPairingUuid(), call.request.headers[HttpHeaders.Authorization]))
        }
        post("/requests/{requestId}/cancel") {
            store.requireMutationAllowed(call.request.origin.remoteHost)
            if (call.receivePairingObject().isNotEmpty()) {
                throw ApiException.badRequest("INVALID_REQUEST", "The cancel body must be an empty JSON object.")
            }
            store.cancel(call.requiredPairingUuid(), call.request.headers[HttpHeaders.Authorization])
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

internal fun ApplicationCall.requirePrincipal(config: RunnerConfig, security: RunnerSecurityStore): String {
    attributes.getOrNull(PRINCIPAL_ATTRIBUTE)?.let { return it }
    val principal = if (config.transportMode == TransportMode.DEVELOPMENT_HTTP) SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL
    else {
        if (request.headers.getAll(HttpHeaders.Authorization)?.size != 1) throw ApiException.unauthorized()
        security.authenticate(request.headers[HttpHeaders.Authorization])
    }
    attributes.put(PRINCIPAL_ATTRIBUTE, principal)
    return principal
}

internal fun runnerAuthentication(config: RunnerConfig, security: RunnerSecurityStore) = createApplicationPlugin("RunnerAuthentication") {
    onCall { call ->
        val path = call.request.path()
        if (path == "/v1" || path.startsWith("/v1/") || path == "/v2" || path.startsWith("/v2/")) {
            call.requirePrincipal(config, security)
        }
    }
}

private val PRINCIPAL_ATTRIBUTE = AttributeKey<String>("reprodroid.authenticatedPrincipal")

private fun ApplicationCall.requiredPairingUuid(): String = parameters["requestId"]
    ?.takeIf { it.matches(UUID_PATTERN) && runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) }
    ?: throw ApiException.unauthorizedPairing()

private suspend inline fun <reified T> ApplicationCall.receivePairing(): T = try {
    PAIRING_JSON.decodeFromString(StrictV2Json.receivePairing(this).toString())
} catch (failure: ApiException) {
    throw failure
} catch (_: SerializationException) {
    throw ApiException.badRequest("INVALID_REQUEST", "The pairing request does not match the contract.")
} catch (_: IllegalArgumentException) {
    throw ApiException.badRequest("INVALID_REQUEST", "The pairing request does not match the contract.")
}

private suspend fun ApplicationCall.receivePairingObject(): JsonObject = try {
    StrictV2Json.receivePairing(this)
} catch (failure: ApiException) {
    throw failure
} catch (_: Exception) {
    throw ApiException.badRequest("INVALID_REQUEST", "The pairing request does not match the contract.")
}

private val PAIRING_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    isLenient = false
}
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
