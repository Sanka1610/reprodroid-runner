package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

internal class SimulatedBuildExecutor(
    private val store: SQLiteJobStore,
    private val stepDelayMillis: Long,
) {
    suspend fun execute(job: StoredJob) {
        require(job.executionMode == ExecutionMode.SIMULATED)
        val outcome = requireNotNull(job.simulationOutcome)

        if (!transition(job.jobId, JobState.BUILDING, 15, "Simulated build started; no external process was launched.")) return
        pause()
        if (!transition(job.jobId, JobState.BUILDING, 40, "Simulated dependency resolution completed.")) return
        pause()
        if (!transition(job.jobId, JobState.BUILDING, 70, "Simulated compilation completed.")) return
        pause()

        if (outcome == SimulationOutcome.FAILURE) {
            store.failIfActive(
                jobId = job.jobId,
                logMessage = "Simulated build failed as requested.",
                progressPercent = 70,
                error = JobError(
                    code = "SIMULATED_BUILD_FAILURE",
                    message = "The simulated build was configured to fail.",
                ),
            )
            return
        }

        if (!transition(
            job.jobId,
            JobState.DISCOVERING_ARTIFACTS,
            90,
            "Discovering simulated APK metadata.",
        )) return
        pause()
        store.completeSimulatedSuccessIfActive(
            jobId = job.jobId,
            artifact = ArtifactMetadata(
                artifactId = UUID.randomUUID().toString(),
                fileName = "simulated-debug.apk",
                sizeBytes = 1_048_576,
                sha256 = "0".repeat(64),
                packageName = "com.example.reprodroid.simulated",
                versionName = "1.0-simulated",
                versionCode = 1,
            ),
        )
    }

    private suspend fun pause() {
        if (stepDelayMillis > 0) delay(stepDelayMillis)
    }

    private fun transition(jobId: String, state: JobState, progress: Int, message: String): Boolean =
        store.transitionIfActive(jobId, state, progress, message)
}

internal class JobCoordinator(
    private val store: SQLiteJobStore,
    coroutineContext: CoroutineContext,
    simulationStepDelayMillis: Long,
) : Closeable {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val queuedJobIds = Channel<String>(Channel.UNLIMITED)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val simulatedExecutor = SimulatedBuildExecutor(store, simulationStepDelayMillis)

    init {
        store.markRunningJobsInterrupted()
        scope.launch {
            for (jobId in queuedJobIds) {
                val worker = launch(start = CoroutineStart.LAZY) { runJob(jobId) }
                runningJobs[jobId] = worker
                worker.start()
                worker.join()
                runningJobs.remove(jobId, worker)
            }
        }
    }

    @Synchronized
    fun create(request: CreateJobRequest): CreateJobResponse {
        validate(request)
        if (request.executionMode == ExecutionMode.REAL_TRUSTED) {
            throw ApiException.forbidden(
                code = "REAL_BUILD_DISABLED",
                message = "REAL_TRUSTED execution is not implemented in Phase 1B.",
            )
        }
        val response = store.createJob(request)
        check(queuedJobIds.trySend(response.jobId).isSuccess) { "The job queue is not accepting work." }
        return response
    }

    fun get(jobId: String): JobResponse = store.getJob(jobId) ?: throw ApiException.notFound()

    fun logs(jobId: String, afterSequence: Long, limit: Int): LogResponse =
        store.getLogs(jobId, afterSequence, limit) ?: throw ApiException.notFound()

    fun artifacts(jobId: String): ArtifactListResponse = ArtifactListResponse(
        store.listArtifacts(jobId) ?: throw ApiException.notFound(),
    )

    fun confirm(jobId: String) {
        get(jobId)
        throw ApiException.conflict(
            code = "CONFIRMATION_NOT_APPLICABLE",
            message = "SIMULATED jobs do not require confirmation.",
        )
    }

    fun validateArtifact(jobId: String, artifactId: String) {
        val artifacts = store.listArtifacts(jobId) ?: throw ApiException.notFound()
        if (artifacts.none { it.artifactId == artifactId }) throw ApiException.artifactNotFound()
    }

    fun cancel(jobId: String) {
        when (store.cancelIfActive(jobId)) {
            CancelJobResult.NOT_FOUND -> throw ApiException.notFound()
            CancelJobResult.ALREADY_TERMINAL -> throw ApiException.conflict(
                code = "JOB_ALREADY_TERMINAL",
                message = "A completed job cannot be cancelled.",
            )
            CancelJobResult.CANCELLED -> {
                runningJobs[jobId]?.cancel(CancellationException("Cancelled through Runner API"))
            }
        }
    }

    fun retry(jobId: String): CreateJobResponse {
        val original = store.getStoredJob(jobId) ?: throw ApiException.notFound()
        if (!original.state.isTerminal) {
            throw ApiException.conflict(
                code = "JOB_NOT_TERMINAL",
                message = "Only a terminal job can be retried.",
            )
        }
        return create(
            CreateJobRequest(
                executionMode = original.executionMode,
                repositoryUrl = original.repositoryUrl,
                revision = original.revision,
                simulationOutcome = original.simulationOutcome,
            ),
        )
    }

    private suspend fun runJob(jobId: String) {
        if (!store.markQueuedIfCreated(jobId)) return
        val job = store.getStoredJob(jobId) ?: return
        try {
            simulatedExecutor.execute(job)
        } catch (_: CancellationException) {
            // cancel() persists the terminal state before cancelling this coroutine.
        } catch (failure: Throwable) {
            store.failIfActive(
                jobId = jobId,
                error = JobError("INTERNAL_EXECUTION_ERROR", "The simulated executor failed unexpectedly."),
                logMessage = failure.message ?: failure::class.simpleName.orEmpty(),
            )
        }
    }

    private fun validate(request: CreateJobRequest) {
        if (request.repositoryUrl.isBlank()) {
            throw ApiException.badRequest("INVALID_REPOSITORY_URL", "repositoryUrl must not be blank.")
        }
        if (request.repositoryUrl.length > MAX_REPOSITORY_URL_LENGTH) {
            throw ApiException.badRequest("INVALID_REPOSITORY_URL", "repositoryUrl is too long.")
        }
        val repositoryUri = runCatching { URI(request.repositoryUrl) }.getOrNull()
        if (
            repositoryUri?.scheme?.lowercase() !in setOf("http", "https") ||
            repositoryUri?.host == null ||
            repositoryUri.userInfo != null ||
            request.repositoryUrl.any(Char::isISOControl)
        ) {
            throw ApiException.badRequest(
                "INVALID_REPOSITORY_URL",
                "repositoryUrl must be an HTTP(S) URL without embedded credentials.",
            )
        }
        if (request.revision.value.isBlank()) {
            throw ApiException.badRequest("INVALID_REVISION", "revision.value must not be blank.")
        }
        if (request.revision.value.length > MAX_REVISION_LENGTH) {
            throw ApiException.badRequest("INVALID_REVISION", "revision.value is too long.")
        }
        if (request.revision.value.any(Char::isISOControl)) {
            throw ApiException.badRequest("INVALID_REVISION", "revision.value contains control characters.")
        }
        if (request.executionMode == ExecutionMode.SIMULATED && request.simulationOutcome == null) {
            throw ApiException.badRequest(
                "SIMULATION_OUTCOME_REQUIRED",
                "simulationOutcome is required for SIMULATED jobs.",
            )
        }
        if (request.executionMode != ExecutionMode.SIMULATED && request.simulationOutcome != null) {
            throw ApiException.badRequest(
                "SIMULATION_OUTCOME_NOT_ALLOWED",
                "simulationOutcome is only valid for SIMULATED jobs.",
            )
        }
    }

    override fun close() {
        queuedJobIds.close()
        scope.cancel()
    }

    private companion object {
        const val MAX_REPOSITORY_URL_LENGTH = 2_048
        const val MAX_REVISION_LENGTH = 256
    }
}
