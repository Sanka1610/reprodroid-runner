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
    val coordinator = JobCoordinator(
        store = store,
        coroutineContext = Dispatchers.IO,
        simulationStepDelayMillis = config.simulationStepDelayMillis,
        realBuildEnabled = config.realBuildEnabled,
        stateDirectory = config.stateDirectory,
        buildSandbox = config.buildSandbox,
    )
    monitor.subscribe(ApplicationStopped) { coordinator.close() }

    install(CallLogging)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
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
                ApiErrorResponse("INVALID_REQUEST", failure.message ?: "The request body is invalid."),
            )
        }
        exception<SerializationException> { call, failure ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("INVALID_REQUEST", failure.message ?: "The request body is invalid."),
            )
        }
        exception<Throwable> { call, failure ->
            logger.error("Unhandled Runner API failure", failure)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse("INTERNAL_ERROR", "The Runner could not complete the request."),
            )
        }
    }

    routing {
        route("/v1") {
            get("/health") {
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
                val request = call.receive<CreateJobRequest>()
                call.respond(HttpStatusCode.Accepted, coordinator.create(request))
            }
            get("/jobs/{jobId}") {
                call.respond(coordinator.get(call.requiredJobId()))
            }
            post("/jobs/{jobId}/confirm") {
                val request = call.receive<ConfirmJobRequest>()
                coordinator.confirm(call.requiredJobId(), request)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/jobs/{jobId}/cancel") {
                coordinator.cancel(call.requiredJobId())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/jobs/{jobId}/retry") {
                call.respond(HttpStatusCode.Accepted, coordinator.retry(call.requiredJobId()))
            }
            get("/jobs/{jobId}/logs") {
                val rawAfterSequence = call.request.queryParameters["afterSequence"]
                val rawLimit = call.request.queryParameters["limit"]
                val afterSequence = rawAfterSequence?.toLongOrNull()
                    ?: if (rawAfterSequence == null) 0L else invalidLogCursor()
                val limit = rawLimit?.toIntOrNull()
                    ?: if (rawLimit == null) 200 else invalidLogCursor()
                if (afterSequence < 0 || limit !in 1..500) {
                    invalidLogCursor()
                }
                call.respond(coordinator.logs(call.requiredJobId(), afterSequence, limit))
            }
            get("/jobs/{jobId}/artifacts") {
                call.respond(coordinator.artifacts(call.requiredJobId()))
            }
            get("/jobs/{jobId}/build-environment-manifest") {
                call.respond(coordinator.buildEnvironmentManifest(call.requiredJobId()))
            }
            get("/jobs/{jobId}/source-scan") {
                call.respond(coordinator.sourceScan(call.requiredJobId()))
            }
            post("/jobs/{jobId}/source-scan/continue") {
                val request = call.receive<ContinueSourceScanRequest>()
                coordinator.continueSourceScan(call.requiredJobId(), request)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/jobs/{jobId}/artifacts/{artifactId}/content") {
                val artifactId = call.parameters["artifactId"]?.takeIf(String::isNotBlank)
                    ?: throw ApiException.badRequest("ARTIFACT_ID_REQUIRED", "artifactId is required.")
                val artifact = coordinator.artifactContent(call.requiredJobId(), artifactId)
                call.response.header(HttpHeaders.ContentType, "application/vnd.android.package-archive")
                call.response.header(HttpHeaders.ContentLength, artifact.metadata.sizeBytes.toString())
                call.response.header(HttpHeaders.ETag, "\"${artifact.metadata.sha256}\"")
                call.respondFile(artifact.path.toFile())
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requiredJobId(): String =
    parameters["jobId"]?.takeIf(String::isNotBlank)
        ?: throw ApiException.badRequest("JOB_ID_REQUIRED", "jobId is required.")

private fun invalidLogCursor(): Nothing = throw ApiException.badRequest(
    "INVALID_LOG_CURSOR",
    "afterSequence must be a non-negative integer and limit must be an integer between 1 and 500.",
)
