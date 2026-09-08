package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
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
    private val realBuildEnabled: Boolean = false,
    private val stateDirectory: Path,
    private val recipeRegistry: BuildRecipeRegistry = BuildRecipeRegistry(),
    processExecutor: ProcessExecutor = SystemProcessExecutor(),
    sourceResolver: SourceResolver? = null,
    wrapperVerifier: WrapperVerifier = WrapperVerifier(),
    private val buildSandbox: BuildSandboxMode = BuildSandboxMode.HOST,
    recoveryEngine: SandboxRecoveryEngine? = null,
    dockerControl: DockerControl? = null,
) : Closeable {
    internal data class ArtifactContent(
        val metadata: ArtifactMetadata,
        val path: Path,
    )

    private val stateLease: SandboxStateLease
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(coroutineContext + supervisor)
    private val dockerControl = dockerControl ?: LocalDockerControl(stateDirectory)
    private val sandboxLifecycle: SandboxLifecycle
    private val queuedJobIds = Channel<String>(Channel.UNLIMITED)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val simulatedExecutor = SimulatedBuildExecutor(store, simulationStepDelayMillis)
    private val sourceResolver = sourceResolver ?: GitSourceResolver(processExecutor, store, stateDirectory)
    private val trustedBuildExecutor: TrustedBuildExecutor
    private val buildManifestPublisher = BuildManifestPublisher(store, stateDirectory)

    init {
        stateLease = SandboxStateLease(stateDirectory)
        try {
            sandboxLifecycle = SandboxLifecycle(store, recoveryEngine ?: DockerRecoveryEngine(this.dockerControl), store.sandboxOwnerId())
            trustedBuildExecutor = TrustedBuildExecutor(
                store = store,
                processExecutor = processExecutor,
                wrapperVerifier = wrapperVerifier,
                stateDirectory = stateDirectory,
                dockerExecutor = DockerBuildExecutor(store, sandboxLifecycle, this.dockerControl, processExecutor, stateDirectory),
            )
            runBlocking { sandboxLifecycle.recover() }
            store.markRunningJobsInterrupted(clearArtifactMetadata = !sandboxLifecycle.cleanupPending)
            store.listResumableSourceScanJobIds().forEach { jobId ->
                check(queuedJobIds.trySend(jobId).isSuccess) { "The job queue is not accepting resumed work." }
            }
            scope.launch {
                for (jobId in queuedJobIds) {
                    val worker = launch(start = CoroutineStart.LAZY) { runJob(jobId) }
                    runningJobs[jobId] = worker
                    worker.start()
                    worker.join()
                    runningJobs.remove(jobId, worker)
                }
            }
        } catch (failure: Throwable) {
            // A failed constructor has no caller that can close it; release its lease explicitly.
            queuedJobIds.close()
            scope.cancel()
            runCatching { stateLease.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    fun create(request: CreateJobRequest, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): CreateJobResponse {
        if (request.genericBuild != null) {
            throw ApiException.badRequest("GENERIC_BUILD_REQUIRES_V2", "Generic builds must use POST /v2/builds.")
        }
        validate(request)
        val normalizedRequest = if (request.executionMode == ExecutionMode.REAL_TRUSTED) {
            request.copy(repositoryUrl = BuildRecipeRegistry.canonicalRepositoryKey(request.repositoryUrl))
        } else {
            request
        }
        if (normalizedRequest.executionMode == ExecutionMode.REAL_TRUSTED) {
            if (sandboxLifecycle.cleanupPending) {
                throw ApiException.serviceUnavailable("SANDBOX_CLEANUP_PENDING", "Owned sandbox resources require recovery before real builds can be accepted.")
            }
            if (!realBuildEnabled) {
                throw ApiException.forbidden(
                    code = "REAL_BUILD_DISABLED",
                    message = "REAL_TRUSTED execution is disabled by Runner configuration.",
                )
            }
            recipeRegistry.requireAllowed(normalizedRequest.repositoryUrl, normalizedRequest.revision)
        }
        val response = store.createJob(normalizedRequest, buildSandbox, principalId)
        check(queuedJobIds.trySend(response.jobId).isSuccess) { "The job queue is not accepting work." }
        return response
    }

    @Synchronized
    fun createGeneric(
        repositoryUrl: String,
        commitSha: String,
        snapshot: GenericBuildSnapshot,
        idempotencyKey: String,
        requestSha256: String,
        principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL,
    ): GenericBuildReceipt {
        if (buildSandbox != BuildSandboxMode.DOCKER) {
            throw ApiException.serviceUnavailable("GENERIC_DOCKER_REQUIRED", "Generic builds require REPRODROID_BUILD_SANDBOX=DOCKER; HOST fallback is forbidden.")
        }
        if (!realBuildEnabled) {
            throw ApiException.forbidden("REAL_BUILD_DISABLED", "REAL_TRUSTED execution is disabled by Runner configuration.")
        }
        if (sandboxLifecycle.cleanupPending) {
            throw ApiException.serviceUnavailable("SANDBOX_CLEANUP_PENDING", "Owned sandbox resources require recovery before generic builds can be accepted.")
        }
        val recipe = GenericBuildContract.recipe(repositoryUrl, commitSha, snapshot)
        val configuration = GenericBuildContract.validate(repositoryUrl, commitSha, snapshot)
        store.requireManagedToolchains(configuration)
        val request = CreateJobRequest(
            executionMode = ExecutionMode.REAL_TRUSTED,
            repositoryUrl = recipe.repositoryUrl,
            revision = recipe.revision,
            genericBuild = snapshot,
        )
        validate(request)
        val receipt = store.createGenericJob(request, idempotencyKey, requestSha256, principalId)
        if (!receipt.existing) {
            check(queuedJobIds.trySend(receipt.response.jobId).isSuccess) { "The job queue is not accepting work." }
        }
        return receipt
    }

    @Synchronized
    fun retryGenericResources(
        comparisonId: String,
        idempotencyKey: String,
        requestSha256: String,
        principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL,
    ): GenericResourceRetryReceipt {
        if (buildSandbox != BuildSandboxMode.DOCKER) {
            throw ApiException.serviceUnavailable("GENERIC_DOCKER_REQUIRED", "Generic resource retry requires REPRODROID_BUILD_SANDBOX=DOCKER.")
        }
        if (!realBuildEnabled) {
            throw ApiException.forbidden("REAL_BUILD_DISABLED", "REAL_TRUSTED execution is disabled by Runner configuration.")
        }
        if (sandboxLifecycle.cleanupPending) {
            throw ApiException.serviceUnavailable("SANDBOX_CLEANUP_PENDING", "Owned sandbox resources require recovery before generic builds can be accepted.")
        }
        val receipt = store.createGenericResourceRetry(comparisonId, idempotencyKey, requestSha256, principalId)
        if (!receipt.existing) {
            check(queuedJobIds.trySend(receipt.response.buildAJobId).isSuccess) { "The job queue is not accepting work." }
            check(queuedJobIds.trySend(receipt.response.buildBJobId).isSuccess) { "The job queue is not accepting work." }
        }
        return receipt
    }

    fun get(jobId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): JobResponse {
        requireOwnedJob(jobId, principalId)
        return store.getJob(jobId) ?: throw ApiException.notFound()
    }

    fun logs(jobId: String, afterSequence: Long, limit: Int, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): LogResponse {
        requireOwnedJob(jobId, principalId)
        return store.getLogs(jobId, afterSequence, limit) ?: throw ApiException.notFound()
    }

    fun artifacts(jobId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): ArtifactListResponse {
        requireOwnedJob(jobId, principalId)
        return ArtifactListResponse(store.listArtifacts(jobId) ?: throw ApiException.notFound())
    }

    fun buildEnvironmentManifest(jobId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): BuildEnvironmentManifestResponse {
        requireOwnedJob(jobId, principalId)
        return buildManifestPublisher.publicManifest(jobId)
    }

    fun sourceScan(jobId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): SourceScanDetailResponse {
        requireOwnedJob(jobId, principalId)
        return store.getSourceScan(jobId) ?: throw ApiException.conflict(
            code = "SOURCE_SCAN_NOT_AVAILABLE",
            message = "Source scan evidence is not available for this job.",
        )
    }

    fun continueSourceScan(jobId: String, request: ContinueSourceScanRequest, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) {
        requireOwnedJob(jobId, principalId)
        if (!request.riskAcknowledged) {
            throw ApiException.forbidden(
                code = "SOURCE_SCAN_RISK_ACKNOWLEDGEMENT_REQUIRED",
                message = "Explicit acknowledgement of the reported source indicators is required.",
            )
        }
        if (!request.scanResultSha256.matches(Regex("[0-9a-f]{64}"))) {
            throw ApiException.conflict(
                code = "SOURCE_SCAN_REVIEW_DIGEST_MISMATCH",
                message = "The source scan review digest does not match the stored result.",
            )
        }
        val result = try {
            store.reviewSourceScan(jobId, request.scanResultSha256, principalId)
        } catch (failure: ApiException) {
            throw failure
        } catch (_: Throwable) {
            store.failIfActive(
                jobId = jobId,
                error = JobError("SOURCE_SCAN_PERSISTENCE_FAILED", "The source scan review could not be persisted."),
                logMessage = "Source scan review persistence failed; the build was not started.",
            )
            throw ApiException.internalServerError(
                code = "SOURCE_SCAN_PERSISTENCE_FAILED",
                message = "The source scan review could not be persisted.",
            )
        }
        when (result) {
            ReviewSourceScanResult.NOT_FOUND -> throw ApiException.notFound()
            ReviewSourceScanResult.DIGEST_MISMATCH -> throw ApiException.conflict(
                code = "SOURCE_SCAN_REVIEW_DIGEST_MISMATCH",
                message = "The source scan review digest does not match the stored result.",
            )
            ReviewSourceScanResult.NOT_REQUIRED,
            ReviewSourceScanResult.RACE,
            -> throw ApiException.conflict(
                code = "SOURCE_SCAN_REVIEW_NOT_REQUIRED",
                message = "This job is not awaiting source scan review.",
            )
            ReviewSourceScanResult.SUCCESS -> {
                store.appendLog(jobId, LogLevel.WARN, "Client acknowledged the source scan findings for the stored result digest.")
                check(queuedJobIds.trySend(jobId).isSuccess) { "The job queue is not accepting resumed work." }
            }
        }
    }

    fun confirm(jobId: String, request: ConfirmJobRequest, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) {
        val job = requireOwnedJob(jobId, principalId)
        if (job.executionMode != ExecutionMode.REAL_TRUSTED) {
            throw ApiException.conflict(
                code = "CONFIRMATION_NOT_APPLICABLE",
                message = "SIMULATED jobs do not require confirmation.",
            )
        }
        if (!request.riskAcknowledged) {
            throw ApiException.forbidden(
                code = "RISK_ACKNOWLEDGEMENT_REQUIRED",
                message = "Explicit acknowledgement of host arbitrary-code-execution risk is required.",
            )
        }
        if (job.state != JobState.AWAITING_CONFIRMATION) {
            throw ApiException.conflict(
                code = "JOB_NOT_AWAITING_CONFIRMATION",
                message = "The job is not awaiting real-build confirmation.",
            )
        }
        val confirmedSha = request.resolvedCommitSha.lowercase()
        if (confirmedSha != job.resolvedCommitSha) {
            throw ApiException.conflict(
                code = "RESOLVED_COMMIT_MISMATCH",
                message = "The confirmed commit does not match the Runner-resolved commit.",
            )
        }
        if (!store.confirmRealJob(jobId, confirmedSha, principalId)) {
            throw ApiException.conflict(
                code = "CONFIRMATION_RACE",
                message = "The job changed state before confirmation was applied.",
            )
        }
        check(queuedJobIds.trySend(jobId).isSuccess) { "The job queue is not accepting work." }
    }

    fun artifactContent(jobId: String, artifactId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): ArtifactContent {
        val job = requireOwnedJob(jobId, principalId)
        val storedArtifact = store.getStoredArtifact(jobId, artifactId) ?: throw ApiException.artifactNotFound()
        if (job.state != JobState.SUCCEEDED) {
            throw ApiException.conflict(
                code = "JOB_NOT_SUCCEEDED",
                message = "Artifact content is available only for a succeeded job.",
            )
        }
        val relativePath = storedArtifact.contentRelativePath ?: throw ApiException.conflict(
            code = "ARTIFACT_CONTENT_UNAVAILABLE",
            message = "This artifact has no downloadable APK content.",
        )
        val stateRoot = stateDirectory.toRealPath()
        val artifactRoot = stateRoot.resolve("artifacts").resolve(jobId).normalize()
        val contentPath = stateRoot.resolve(relativePath).normalize()
        val artifactRootRealPath = runCatching { artifactRoot.toRealPath() }.getOrNull()
        val contentRealPath = runCatching { contentPath.toRealPath() }.getOrNull()
        if (
            artifactRootRealPath == null || contentRealPath == null ||
            !artifactRootRealPath.startsWith(stateRoot) ||
            !contentRealPath.startsWith(artifactRootRealPath) ||
            !Files.isRegularFile(contentPath, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(contentPath) != storedArtifact.metadata.sizeBytes ||
            sha256(contentPath) != storedArtifact.metadata.sha256
        ) {
            throw ApiException.conflict(
                code = "ARTIFACT_CONTENT_INVALID",
                message = "The stored APK no longer matches its registered metadata.",
            )
        }
        return ArtifactContent(storedArtifact.metadata, contentRealPath)
    }

    fun cancel(jobId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) {
        requireOwnedJob(jobId, principalId)
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

    fun retry(jobId: String, principalId: String = SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL): CreateJobResponse {
        val original = requireOwnedJob(jobId, principalId)
        if (original.genericBuild != null) {
            throw ApiException.conflict("GENERIC_RETRY_REQUIRES_COMPARISON", "Generic resource retry must use the comparison retry-resource endpoint.")
        }
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
            principalId,
        )
    }

    private fun requireOwnedJob(jobId: String, principalId: String): StoredJob =
        store.getStoredJob(jobId)?.takeIf { it.principalId == principalId } ?: throw ApiException.notFound()

    private suspend fun runJob(jobId: String) {
        try {
            val job = store.getStoredJob(jobId) ?: return
            when (job.executionMode) {
                ExecutionMode.SIMULATED -> {
                    if (!store.markQueuedIfCreated(jobId)) return
                    simulatedExecutor.execute(store.getStoredJob(jobId) ?: return)
                }
                ExecutionMode.REAL_TRUSTED -> runRealJob(job)
            }
        } catch (_: CancellationException) {
            // cancel() persists the terminal state before cancelling this coroutine.
        } catch (failure: TrustedBuildFailure) {
            store.failIfActive(
                jobId = jobId,
                error = JobError(failure.code, failure.message),
                logMessage = failure.message,
            )
        } catch (failure: Throwable) {
            store.failIfActive(
                jobId = jobId,
                error = JobError("INTERNAL_EXECUTION_ERROR", "The job executor failed unexpectedly."),
                logMessage = "The job executor failed unexpectedly; private diagnostics are required.",
            )
        }
    }

    private suspend fun runRealJob(job: StoredJob) {
        if (sandboxLifecycle.cleanupPending) return
        val recipe = job.genericBuild?.let { GenericBuildContract.recipe(job.repositoryUrl, job.revision.value, it) }
            ?: recipeRegistry.find(job.repositoryUrl, job.revision)
            ?: throw TrustedBuildFailure("RECIPE_NOT_FOUND", "The persisted repository no longer has an allowlisted recipe.")
        when (job.state) {
            JobState.CREATED -> {
                val beganResolving = try {
                    store.beginResolvingRealJob(job.jobId, recipe)
                } catch (_: Throwable) {
                    throw TrustedBuildFailure(
                        "DEPENDENCY_LOCK_AUDIT_PERSISTENCE_FAILED",
                        "Runner could not persist the effective dependency pinning policy.",
                    )
                }
                if (!beganResolving) return
                val resolvedCommit = sourceResolver.resolve(recipe, job.revision, job.jobId)
                store.awaitRealConfirmation(job.jobId, resolvedCommit)
            }
            JobState.QUEUED -> trustedBuildExecutor.execute(job, recipe)
            else -> Unit
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
        if (request.genericBuild != null && (request.executionMode != ExecutionMode.REAL_TRUSTED || request.revision.type != RevisionType.COMMIT)) {
            throw ApiException.badRequest("INVALID_GENERIC_BUILD", "Generic builds require REAL_TRUSTED and a full commit revision.")
        }
    }

    override fun close() {
        queuedJobIds.close()
        try {
            runBlocking { supervisor.cancelAndJoin() }
        } finally {
            stateLease.close()
        }
    }

    private companion object {
        const val MAX_REPOSITORY_URL_LENGTH = 2_048
        const val MAX_REVISION_LENGTH = 256
    }
}
