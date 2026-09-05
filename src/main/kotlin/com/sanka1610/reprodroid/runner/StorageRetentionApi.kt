package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.time.Instant

internal fun Route.storageRetentionV2Routes(store: StorageRetentionStore, genericExecutionEnabled: Boolean = false) {
    route("/v2") {
        get("/capabilities") {
            call.respond(
                V2CapabilitiesResponse(
                    apiVersion = "v2",
                    foundationContractVersion = 1,
                    runnerId = store.runnerId(),
                    runnerVersion = "0.1.0-alpha01",
                    capabilities = buildList {
                        addAll(listOf(
                        V2Capability("foundation", 1),
                        V2Capability("storage-retention", 1),
                        V2Capability("toolchain-install", 1),
                        ))
                        if (genericExecutionEnabled) {
                            add(V2Capability("generic-build", 1))
                            add(V2Capability("apk-comparison", 1))
                        }
                    },
                ),
            )
        }
        get("/operations/{operationId}") {
            call.respond(store.operation(call.requiredCanonicalUuid("operationId"), LOCAL_PRINCIPAL))
        }
        get("/storage/summary") {
            call.respond(store.storageSummary())
        }
        post("/retention/holds") {
            val key = call.requireMutationHeaders()
            val command = parseHoldCreate(StrictV2Json.receive(call))
            call.respondReceipt(store.createHold(LOCAL_PRINCIPAL, key, command))
        }
        post("/retention/holds/{holdId}/release") {
            val key = call.requireMutationHeaders()
            val holdId = call.requiredCanonicalUuid("holdId")
            val request = parseReason(
                StrictV2Json.receive(call),
                setOf("REFERENCE_RELEASED", "APP_REMOVED", "COMPARISON_REPLACED"),
            )
            call.respondReceipt(store.releaseHold(LOCAL_PRINCIPAL, key, holdId, request))
        }
        post("/storage/reservations") {
            val key = call.requireMutationHeaders()
            val command = parseReservationCreate(StrictV2Json.receive(call))
            call.respondReceipt(store.createReservation(LOCAL_PRINCIPAL, key, command))
        }
        post("/storage/reservations/{reservationId}/release") {
            val key = call.requireMutationHeaders()
            val reservationId = call.requiredCanonicalUuid("reservationId")
            val request = parseReason(
                StrictV2Json.receive(call),
                setOf("OPERATION_CANCELLED", "OPERATION_COMPLETED", "RESERVATION_NOT_NEEDED"),
            )
            call.respondReceipt(store.releaseReservation(LOCAL_PRINCIPAL, key, reservationId, request))
        }
        post("/cleanup/previews") {
            val key = call.requireMutationHeaders()
            val command = parseCleanupPreview(StrictV2Json.receive(call))
            call.respondReceipt(store.createCleanupPreview(LOCAL_PRINCIPAL, key, command))
        }
        get("/cleanup/previews/{previewId}") {
            call.respond(store.cleanupPreview(call.requiredCanonicalUuid("previewId"), LOCAL_PRINCIPAL))
        }
        post("/cleanup/previews/{previewId}/execute") {
            val key = call.requireMutationHeaders()
            val previewId = call.requiredCanonicalUuid("previewId")
            val command = parseCleanupExecute(StrictV2Json.receive(call))
            call.respondReceipt(store.executeCleanup(LOCAL_PRINCIPAL, key, previewId, command))
        }
        get("/cleanup/runs/{cleanupRunId}") {
            call.respond(store.cleanupRun(call.requiredCanonicalUuid("cleanupRunId"), LOCAL_PRINCIPAL))
        }
    }
}

private suspend fun ApplicationCall.respondReceipt(receipt: OperationReceipt) {
    respond(if (receipt.existing) HttpStatusCode.OK else HttpStatusCode.Accepted, receipt.response)
}

private fun ApplicationCall.requireMutationHeaders(): String {
    val contract = request.headers[CONTRACT_HEADER]
        ?: throw ApiException.badRequest("CONTRACT_REQUIRED", "$CONTRACT_HEADER is required.")
    if (contract != CONTRACT_VALUE) {
        throw ApiException.conflict("CONTRACT_MISMATCH", "The requested operation contract is not supported.")
    }
    val key = request.headers[IDEMPOTENCY_HEADER]
        ?: throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "$IDEMPOTENCY_HEADER is required.")
    return requireCanonicalUuid(key, "Idempotency-Key")
}

private fun ApplicationCall.requiredCanonicalUuid(name: String): String =
    parameters[name]?.let { requireCanonicalUuid(it, name) }
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name is required.")

private fun parseHoldCreate(body: JsonObject): HoldCreateCommand {
    body.requireFields(setOf("resource", "reason", "clientReference"))
    val resource = body.requiredObject("resource").also { it.requireFields(setOf("kind", "id")) }
    val kind = resource.requiredEnum<RetentionResourceKind>("kind")
    val resourceId = requireCanonicalUuid(resource.requiredString("id"), "resource.id")
    val reason = body.requiredString("reason").requireOneOf(
        "reason",
        setOf("CURRENT_COMPARISON", "CURRENT_RELEASE", "EXPORT_SNAPSHOT"),
    )
    val reference = body.requiredObject("clientReference").also { it.requireFields(setOf("type", "id")) }
    val referenceType = reference.requiredString("type").requireOneOf(
        "clientReference.type",
        setOf("REGISTERED_APP", "COMPARISON"),
    )
    val referenceId = requireCanonicalUuid(reference.requiredString("id"), "clientReference.id")
    val normalized = buildJsonObject {
        put("resource", buildJsonObject {
            put("kind", kind.name)
            put("id", resourceId)
        })
        put("reason", reason)
        put("clientReference", buildJsonObject {
            put("type", referenceType)
            put("id", referenceId)
        })
    }
    return HoldCreateCommand(kind, resourceId, reason, referenceType, referenceId, normalized)
}

private fun parseReservationCreate(body: JsonObject): ReservationCreateCommand {
    body.requireFields(setOf("area", "purpose", "resource", "requestedBytes"))
    val area = body.requiredEnum<StorageArea>("area")
    val purpose = body.requiredString("purpose").requireOneOf("purpose", setOf("CLIENT_OPERATION"))
    val resource = body.requiredObject("resource").also { it.requireFields(setOf("kind", "id")) }
    val kind = resource.requiredEnum<RetentionResourceKind>("kind")
    val resourceId = requireCanonicalUuid(resource.requiredString("id"), "resource.id")
    val requested = body.requiredDecimalLong("requestedBytes", 1, 16L * 1024 * 1024 * 1024)
    val normalized = buildJsonObject {
        put("area", area.name)
        put("purpose", purpose)
        put("resource", buildJsonObject {
            put("kind", kind.name)
            put("id", resourceId)
        })
        put("requestedBytes", requested.toString())
    }
    return ReservationCreateCommand(area, purpose, kind, resourceId, requested, normalized)
}

private fun parseReason(body: JsonObject, allowed: Set<String>): JsonObject {
    body.requireFields(setOf("reason"))
    val reason = body.requiredString("reason").requireOneOf("reason", allowed)
    return buildJsonObject { put("reason", reason) }
}

private fun parseCleanupPreview(body: JsonObject): CleanupPreviewCommand {
    body.requireFields(setOf("area", "resourceKinds", "eligibleBefore", "resourceIds"))
    val area = body.requiredEnum<StorageArea>("area")
    val resourceKinds = body.requiredStringArray("resourceKinds", 1, 5)
        .map { raw ->
            CleanupResourceKind.entries.singleOrNull { it.name == raw }
                ?: throw ApiException.badRequest("INVALID_REQUEST", "resourceKinds contains an unsupported value.")
        }
    if (resourceKinds.toSet().size != resourceKinds.size) {
        throw ApiException.badRequest("INVALID_REQUEST", "resourceKinds must not contain duplicates.")
    }
    val eligibleBefore = body.requiredString("eligibleBefore")
    val eligibleInstant = runCatching { Instant.parse(eligibleBefore) }.getOrNull()
        ?: throw ApiException.badRequest("INVALID_REQUEST", "eligibleBefore must be a UTC RFC 3339 timestamp.")
    if (eligibleInstant.isAfter(Instant.now())) {
        throw ApiException.badRequest("INVALID_REQUEST", "eligibleBefore must not be in the future.")
    }
    val resourceIds = body.requiredStringArray("resourceIds", 0, 100)
        .map { requireCanonicalUuid(it, "resourceIds") }
    if (resourceIds.toSet().size != resourceIds.size) {
        throw ApiException.badRequest("INVALID_REQUEST", "resourceIds must not contain duplicates.")
    }
    val normalized = buildJsonObject {
        put("area", area.name)
        put("resourceKinds", buildJsonArray { resourceKinds.map(CleanupResourceKind::name).sorted().forEach(::add) })
        put("eligibleBefore", eligibleInstant.toString())
        put("resourceIds", buildJsonArray { resourceIds.sorted().forEach(::add) })
    }
    return CleanupPreviewCommand(
        area = area,
        resourceKinds = resourceKinds.toSet(),
        eligibleBefore = eligibleInstant.toString(),
        resourceIds = resourceIds.toSet(),
        normalizedRequest = normalized,
    )
}

private fun parseCleanupExecute(body: JsonObject): CleanupExecuteCommand {
    body.requireFields(setOf("itemIds"))
    val itemIds = body.requiredStringArray("itemIds", 1, 100)
        .map { requireCanonicalUuid(it, "itemIds") }
    if (itemIds.toSet().size != itemIds.size) {
        throw ApiException.badRequest("INVALID_REQUEST", "itemIds must not contain duplicates.")
    }
    return CleanupExecuteCommand(
        itemIds = itemIds,
        normalizedRequest = buildJsonObject {
            put("itemIds", buildJsonArray { itemIds.sorted().forEach(::add) })
        },
    )
}

private fun JsonObject.requireFields(expected: Set<String>) {
    if (keys != expected) {
        throw ApiException.badRequest("INVALID_REQUEST", "The request contains missing or unknown fields.")
    }
}

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name]?.let { element -> runCatching { element.jsonObject }.getOrNull() }
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name must be a JSON object.")

private fun JsonObject.requiredString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name must be a string.")
    if (!primitive.isString) throw ApiException.badRequest("INVALID_REQUEST", "$name must be a string.")
    return primitive.content
}

private fun JsonObject.requiredStringArray(name: String, minimum: Int, maximum: Int): List<String> {
    val array = this[name] as? JsonArray
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name must be an array.")
    if (array.size !in minimum..maximum) {
        throw ApiException.badRequest("INVALID_REQUEST", "$name contains an unsupported number of items.")
    }
    return array.map { element ->
        val primitive = element as? JsonPrimitive
            ?: throw ApiException.badRequest("INVALID_REQUEST", "$name must contain strings.")
        if (!primitive.isString) throw ApiException.badRequest("INVALID_REQUEST", "$name must contain strings.")
        primitive.content
    }
}

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T =
    enumValues<T>().singleOrNull { it.name == requiredString(name) }
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name contains an unsupported value.")

private fun JsonObject.requiredDecimalLong(name: String, minimum: Long, maximum: Long): Long {
    val raw = requiredString(name)
    if (!DECIMAL_PATTERN.matches(raw)) {
        throw ApiException.badRequest("INVALID_REQUEST", "$name must be a canonical decimal string.")
    }
    val value = raw.toLongOrNull()
        ?: throw ApiException.badRequest("INVALID_REQUEST", "$name is outside the supported range.")
    if (value !in minimum..maximum) {
        throw ApiException.badRequest("INVALID_REQUEST", "$name is outside the supported range.")
    }
    return value
}

private fun String.requireOneOf(name: String, allowed: Set<String>): String {
    if (this !in allowed) throw ApiException.badRequest("INVALID_REQUEST", "$name contains an unsupported value.")
    return this
}

internal fun requireCanonicalUuid(value: String, field: String): String {
    if (!UUID_PATTERN.matches(value)) {
        throw ApiException.badRequest("INVALID_REQUEST", "$field must be a canonical lowercase UUID.")
    }
    return value
}

private const val LOCAL_PRINCIPAL = "local-development"
private const val CONTRACT_HEADER = "X-ReproDroid-Contract"
private const val CONTRACT_VALUE = "storage-retention@1"
private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val DECIMAL_PATTERN = Regex("0|[1-9][0-9]*")
