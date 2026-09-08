package com.sanka1610.reprodroid.runner

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message) {
    companion object {
        fun badRequest(code: String, message: String) = ApiException(HttpStatusCode.BadRequest, code, message)
        fun forbidden(code: String, message: String) = ApiException(HttpStatusCode.Forbidden, code, message)
        fun notFound() = ApiException(HttpStatusCode.NotFound, "JOB_NOT_FOUND", "The requested job does not exist.")
        fun notFound(code: String, message: String) = ApiException(HttpStatusCode.NotFound, code, message)
        fun artifactNotFound() =
            ApiException(HttpStatusCode.NotFound, "ARTIFACT_NOT_FOUND", "The requested artifact does not exist.")
        fun conflict(code: String, message: String) = ApiException(HttpStatusCode.Conflict, code, message)
        fun serviceUnavailable(code: String, message: String) = ApiException(HttpStatusCode.ServiceUnavailable, code, message)
        fun internalServerError(code: String, message: String) =
            ApiException(HttpStatusCode.InternalServerError, code, message)
    }
}

fun Application.runnerModule(config: RunnerConfig) {
    val logger = LoggerFactory.getLogger("ReproDroidRunner")
    val store = SQLiteJobStore(config.stateDirectory)
    val security = RunnerSecurityStore(config.stateDirectory)
    val coordinator = JobCoordinator(
        store = store,
        coroutineContext = Dispatchers.IO,
        simulationStepDelayMillis = config.simulationStepDelayMillis,
        realBuildEnabled = config.realBuildEnabled,
        stateDirectory = config.stateDirectory,
        buildSandbox = config.buildSandbox,
    )
    val storageRetention = StorageRetentionStore(config.stateDirectory)
    val toolchains = if (config.apiV2Enabled) {
        ToolchainStore(config.stateDirectory, storageRetention.runnerId(), ToolchainCatalog.load()).also { it.startRecovery() }
    } else {
        null
    }
    monitor.subscribe(ApplicationStopped) {
        coordinator.close()
        toolchains?.close()
    }

    install(CallLogging) {
        // URLs, query strings, bodies and exception messages can contain submitted secrets.
        format { call -> "Runner response status=${call.response.status()?.value ?: 0}" }
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
                encodeDefaults = true
            },
        )
    }
    install(StatusPages) {
        exception<ApiException> { call, failure ->
            call.respond(failure.status, ApiErrorResponse(failure.code, failure.message))
        }
        exception<BadRequestException> { call, failure ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("INVALID_REQUEST", "The request body is invalid."),
            )
        }
        exception<SerializationException> { call, failure ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("INVALID_REQUEST", "The request body is invalid."),
            )
        }
        exception<Throwable> { call, failure ->
            logger.error("Unhandled Runner API failure ({})", failure.javaClass.simpleName)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse("INTERNAL_ERROR", "The Runner could not complete the request."),
            )
        }
    }
    install(runnerAuthentication(config, security))

    routing {
        if (config.transportMode == TransportMode.PAIRED_HTTPS) pairingRoutes(security)
        route("/v1") {
            get("/health") {
                call.requirePrincipal(config, security)
                call.respond(
                    HealthResponse(
                        runnerVersion = "0.1.0-alpha01",
                        apiVersion = "v1",
                        realBuildEnabled = config.realBuildEnabled,
                        databaseReady = store.isReady(),
                    ),
                )
            }
            post("/jobs") {
                val principalId = call.requirePrincipal(config, security)
                if (config.apiV2Enabled) apiUpgradeRequired()
                val request = call.receive<CreateJobRequest>()
                call.respond(HttpStatusCode.Accepted, coordinator.create(request, principalId))
            }
            get("/jobs/{jobId}") {
                val principalId = call.requirePrincipal(config, security)
                call.respond(coordinator.get(call.requiredJobId(), principalId))
            }
            post("/jobs/{jobId}/confirm") {
                val principalId = call.requirePrincipal(config, security)
                if (config.apiV2Enabled) apiUpgradeRequired()
                val request = call.receive<ConfirmJobRequest>()
                coordinator.confirm(call.requiredJobId(), request, principalId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/jobs/{jobId}/cancel") {
                val principalId = call.requirePrincipal(config, security)
                coordinator.cancel(call.requiredJobId(), principalId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/jobs/{jobId}/retry") {
                val principalId = call.requirePrincipal(config, security)
                if (config.apiV2Enabled) apiUpgradeRequired()
                call.respond(HttpStatusCode.Accepted, coordinator.retry(call.requiredJobId(), principalId))
            }
            get("/jobs/{jobId}/logs") {
                val principalId = call.requirePrincipal(config, security)
                val rawAfterSequence = call.request.queryParameters["afterSequence"]
                val rawLimit = call.request.queryParameters["limit"]
                val afterSequence = rawAfterSequence?.toLongOrNull()
                    ?: if (rawAfterSequence == null) 0L else invalidLogCursor()
                val limit = rawLimit?.toIntOrNull()
                    ?: if (rawLimit == null) 200 else invalidLogCursor()
                if (afterSequence < 0 || limit !in 1..500) {
                    invalidLogCursor()
                }
                call.respond(coordinator.logs(call.requiredJobId(), afterSequence, limit, principalId))
            }
            get("/jobs/{jobId}/artifacts") {
                val principalId = call.requirePrincipal(config, security)
                call.respond(coordinator.artifacts(call.requiredJobId(), principalId))
            }
            get("/jobs/{jobId}/build-environment-manifest") {
                val principalId = call.requirePrincipal(config, security)
                call.respond(coordinator.buildEnvironmentManifest(call.requiredJobId(), principalId))
            }
            get("/jobs/{jobId}/source-scan") {
                val principalId = call.requirePrincipal(config, security)
                call.respond(coordinator.sourceScan(call.requiredJobId(), principalId))
            }
            post("/jobs/{jobId}/source-scan/continue") {
                val principalId = call.requirePrincipal(config, security)
                if (config.apiV2Enabled) apiUpgradeRequired()
                val request = call.receive<ContinueSourceScanRequest>()
                coordinator.continueSourceScan(call.requiredJobId(), request, principalId)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/jobs/{jobId}/artifacts/{artifactId}/content") {
                val principalId = call.requirePrincipal(config, security)
                val artifactId = call.parameters["artifactId"]?.takeIf(String::isNotBlank)
                    ?: throw ApiException.badRequest("ARTIFACT_ID_REQUIRED", "artifactId is required.")
                val artifact = coordinator.artifactContent(call.requiredJobId(), artifactId, principalId)
                call.response.header(HttpHeaders.ContentType, "application/vnd.android.package-archive")
                call.response.header(HttpHeaders.ContentLength, artifact.metadata.sizeBytes.toString())
                call.response.header(HttpHeaders.ETag, "\"${artifact.metadata.sha256}\"")
                call.respondFile(artifact.path.toFile())
            }
        }
        if (config.apiV2Enabled) {
            storageRetentionV2Routes(
                storageRetention,
                genericExecutionEnabled = config.realBuildEnabled && config.buildSandbox == BuildSandboxMode.DOCKER,
                config = config,
                security = security,
            )
            toolchainV2Routes(requireNotNull(toolchains), config, security)
            genericBuildV2Routes(coordinator, store, config, security)
            route("/v2/authentication") {
                post("/self-revoke") {
                    val principalId = call.requirePrincipal(config, security)
                    val body = StrictV2Json.receive(call)
                    if (body.isNotEmpty()) throw ApiException.badRequest("INVALID_REQUEST", "The request body must be an empty JSON object.")
                    call.respond(security.selfRevoke(principalId))
                }
            }
        }
    }
}

private fun apiUpgradeRequired(): Nothing = throw ApiException(
    HttpStatusCode.UpgradeRequired,
    "API_UPGRADE_REQUIRED",
    "This Runner does not accept v1 execution mutations.",
)

private fun io.ktor.server.application.ApplicationCall.requiredJobId(): String =
    parameters["jobId"]?.takeIf(String::isNotBlank)
        ?: throw ApiException.badRequest("JOB_ID_REQUIRED", "jobId is required.")

private fun invalidLogCursor(): Nothing = throw ApiException.badRequest(
    "INVALID_LOG_CURSOR",
    "afterSequence must be a non-negative integer and limit must be an integer between 1 and 500.",
)
