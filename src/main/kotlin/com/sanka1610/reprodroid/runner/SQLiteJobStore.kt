package com.sanka1610.reprodroid.runner

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.sqlite.SQLiteConfig
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class StoredJob(
    val jobId: String,
    val principalId: String,
    val executionMode: ExecutionMode,
    val repositoryUrl: String,
    val revision: RequestedRevision,
    val simulationOutcome: SimulationOutcome?,
    val resolvedCommitSha: String?,
    val state: JobState,
    val sandbox: SandboxSnapshot? = null,
    val genericBuild: GenericBuildSnapshot? = null,
)

internal data class GenericBuildReceipt(val response: CreateJobResponse, val existing: Boolean)
internal data class GenericComparisonReceipt(val response: GenericComparisonResponse, val existing: Boolean)
internal data class GenericResourceRetryReceipt(val response: GenericResourceRetryResponse, val existing: Boolean)

internal data class StoredArtifact(
    val metadata: ArtifactMetadata,
    val contentRelativePath: String?,
)

internal data class StoredBuildManifestAudit(
    val relativePath: String,
    val sha256: String,
)

internal data class StoredSourceScan(
    val detail: SourceScanDetailResponse,
    val canonicalBytes: ByteArray,
    val requiresReview: Boolean,
    val reviewed: Boolean,
)

internal enum class ReviewSourceScanResult {
    SUCCESS,
    NOT_FOUND,
    NOT_REQUIRED,
    DIGEST_MISMATCH,
    RACE,
}

internal enum class CancelJobResult {
    CANCELLED,
    NOT_FOUND,
    ALREADY_TERMINAL,
}

class SQLiteJobStore(
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val migrationPolicy: RunnerMigrationPolicy = RunnerMigrationPolicy(),
) {
    private val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
    private val logDirectory = stateDirectory.resolve("logs")

    init {
        // Prepare private paths before loading SQLite. JDBC may initialize SLF4J,
        // which in turn opens the state-local Logback appender.
        RunnerDatabaseMigrationGate.prepareStateDirectory(stateDirectory)
        RunnerLogging.prepare(stateDirectory)
        Class.forName("org.sqlite.JDBC")
        RunnerDatabaseMigrationGate.initialize(stateDirectory, SCHEMA_VERSION, migrationPolicy) { initializeSchema(it) }
        prepareLogDirectory()
    }

    @Synchronized
    fun createJob(
        request: CreateJobRequest,
        sandboxMode: BuildSandboxMode = BuildSandboxMode.HOST,
        principalId: String = LOCAL_DEVELOPMENT_PRINCIPAL,
    ): CreateJobResponse {
        val jobId = UUID.randomUUID().toString()
        val now = Instant.now(clock).toString()
        val snapshot = if (request.executionMode == ExecutionMode.REAL_TRUSTED) {
            request.genericBuild?.let { SandboxSnapshot.newGenericJob(it.memoryBytes) } ?: SandboxSnapshot.newJob(sandboxMode)
        } else null
        connection().use { connection ->
            connection.autoCommit = false
            try {
                insertJob(connection, jobId, request, snapshot, now, principalId)
                connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        try {
            appendLog(jobId, LogLevel.INFO, "Job created in ${request.executionMode} mode.")
        } catch (failure: Throwable) {
            runCatching { deleteJob(jobId) }
            runCatching { Files.deleteIfExists(logPath(jobId)) }
            throw failure
        }
        return CreateJobResponse(jobId, JobState.CREATED)
    }

    private fun insertJob(
        connection: Connection,
        jobId: String,
        request: CreateJobRequest,
        snapshot: SandboxSnapshot?,
        now: String,
        principalId: String,
    ) {
        requireActivePrincipal(connection, principalId)
        connection.prepareStatement(
            """
            INSERT INTO jobs (
                job_id, principal_id, execution_mode, repository_url, revision_type, revision_value,
                simulation_outcome, state, progress_percent, requires_confirmation,
                created_at, updated_at, sandbox_mode, sandbox_origin, sandbox_profile_id,
                sandbox_snapshot, sandbox_snapshot_sha256, sandbox_cleanup_status, manifest_format
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.setString(2, principalId)
            statement.setString(3, request.executionMode.name)
            statement.setString(4, request.repositoryUrl)
            statement.setString(5, request.revision.type.name)
            statement.setString(6, request.revision.value)
            statement.setString(7, request.simulationOutcome?.name)
            statement.setString(8, JobState.CREATED.name)
            statement.setString(9, now)
            statement.setString(10, now)
            statement.setString(11, snapshot?.jobSandbox?.mode?.name)
            statement.setString(12, snapshot?.jobSandbox?.origin?.name)
            statement.setString(13, snapshot?.jobSandbox?.profileId)
            statement.setString(14, snapshot?.canonicalProfile)
            statement.setString(15, snapshot?.profileSha256)
            statement.setString(16, snapshot?.jobSandbox?.cleanupStatus?.name)
            statement.setString(17, snapshot?.manifestFormat?.name)
            statement.executeUpdate()
        }
    }

    @Synchronized
    internal fun createGenericJob(request: CreateJobRequest, idempotencyKey: String, requestSha256: String, principalId: String): GenericBuildReceipt {
        requireNotNull(request.genericBuild)
        connection().use { connection ->
            requireActivePrincipal(connection, principalId)
            connection.prepareStatement(
                "SELECT request_sha256,result_id FROM operations WHERE principal_id=? AND operation_kind='GENERIC_BUILD_CREATE' AND idempotency_key=?",
            ).use { statement ->
                statement.setString(1, principalId)
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { row ->
                    if (row.next()) {
                        if (row.getString(1) != requestSha256) {
                            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request.")
                        }
                        val jobId = row.getString(2)
                        val job = getStoredJob(jobId) ?: throw ApiException.serviceUnavailable(
                            "RECONCILIATION_REQUIRED", "The accepted generic build is missing its Job record.",
                        )
                        return GenericBuildReceipt(CreateJobResponse(jobId, job.state), true)
                    }
                }
            }
        }
        val created = createJob(request, BuildSandboxMode.DOCKER, principalId)
        val now = Instant.now(clock).toString()
        try {
            connection().use { connection ->
                connection.autoCommit = false
                try {
                    requireActivePrincipal(connection, principalId)
                    connection.prepareStatement(
                        "INSERT INTO generic_build_requests(job_id,request_json,configuration_sha256,comparison_id,attempt,retry_of_job_id,memory_bytes) VALUES(?,?,?,?,?,?,?)",
                    ).use { statement ->
                        statement.setString(1, created.jobId)
                        statement.setString(2, GENERIC_JSON.encodeToString(request.genericBuild))
                        statement.setString(3, request.genericBuild.configurationSha256)
                        statement.setString(4, request.genericBuild.comparisonId)
                        statement.setString(5, request.genericBuild.attempt.name)
                        statement.setString(6, request.genericBuild.retryOfJobId)
                        statement.setLong(7, request.genericBuild.memoryBytes)
                        statement.executeUpdate()
                    }
                    val operationId = UUID.randomUUID().toString()
                    connection.prepareStatement(
                        "INSERT INTO operations(operation_id,principal_id,operation_kind,idempotency_key,request_sha256,contract_id,contract_version,state,result_type,result_id,created_at,updated_at) VALUES(?,?,?,?,?,'generic-build',1,'COMPLETED','JOB',?,?,?)",
                    ).use { statement ->
                        statement.setString(1, operationId)
                        statement.setString(2, principalId)
                        statement.setString(3, "GENERIC_BUILD_CREATE")
                        statement.setString(4, idempotencyKey)
                        statement.setString(5, requestSha256)
                        statement.setString(6, created.jobId)
                        statement.setString(7, now)
                        statement.setString(8, now)
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            runCatching { deleteJob(created.jobId) }
            throw failure
        }
        return GenericBuildReceipt(created, false)
    }

    @Synchronized
    internal fun getStoredJob(jobId: String): StoredJob? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT *
            FROM jobs WHERE job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                result.toStoredJob().copy(genericBuild = genericBuild(connection, jobId))
            }
        }
    }

    @Synchronized
    fun getJob(jobId: String): JobResponse? = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM jobs WHERE job_id = ?").use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                result.toJobResponse(
                    latestLogSequence = latestLogSequence(connection, jobId),
                    artifacts = listArtifacts(connection, jobId),
                    sourceScan = sourceScanSummary(connection, jobId, result),
                    genericBuild = genericBuild(connection, jobId),
                    discovery = genericDiscovery(connection, jobId),
                )
            }
        }
    }

    @Synchronized
    internal fun recordGenericDiscovery(evidence: GenericDiscoveryEvidence): Boolean = connection().use { connection ->
        connection.prepareStatement(
            "INSERT OR REPLACE INTO generic_discovery_evidence(job_id,evidence_json,output_sha256,output_bytes,observed_at) VALUES(?,?,?,?,?)",
        ).use { statement ->
            statement.setString(1, evidence.jobId)
            statement.setString(2, GENERIC_JSON.encodeToString(evidence))
            statement.setString(3, evidence.outputSha256)
            statement.setLong(4, evidence.outputBytes)
            statement.setString(5, evidence.observedAt)
            statement.executeUpdate() == 1
        }
    }

    @Synchronized
    internal fun genericDiscovery(jobId: String): GenericDiscoveryEvidence? = connection().use { genericDiscovery(it, jobId) }

    @Synchronized
    internal fun requireManagedToolchains(configuration: GenericBuildConfiguration): ManagedToolchains {
        val required = listOf(
            Triple("JDK", "21.0.12+1", "toolchains/jdk/21.0.12+1"),
            Triple("GRADLE", configuration.gradleVersion, "toolchains/gradle/${configuration.gradleVersion}"),
            Triple(
                "ANDROID_PLATFORM",
                if (configuration.compileSdk == 36) "36-r02" else "37.0-r02",
                "toolchains/android/platforms/${androidPlatformDirectoryName(configuration.compileSdk)}",
            ),
            Triple("ANDROID_BUILD_TOOLS", configuration.buildToolsVersion, "toolchains/android/build-tools/${configuration.buildToolsVersion}"),
        )
        connection().use { connection ->
            required.forEach { (component, version, relativePath) ->
                connection.prepareStatement(
                    "SELECT relative_path FROM toolchain_inventory WHERE component=? AND version=? AND state='VERIFIED'",
                ).use { statement ->
                    statement.setString(1, component)
                    statement.setString(2, version)
                    statement.executeQuery().use { row ->
                        if (!row.next() || row.getString(1) != relativePath) {
                            throw ApiException.conflict("TOOLCHAIN_NOT_INSTALLED", "$component $version is not verified in the managed store.")
                        }
                    }
                }
            }
        }
        return ManagedToolchains(
            stateDirectory.resolve("toolchains/jdk/21.0.12+1"),
            stateDirectory.resolve("toolchains/gradle/${configuration.gradleVersion}"),
            stateDirectory.resolve("toolchains/android"),
        ).validate(configuration)
    }

    @Synchronized
    internal fun createGenericComparison(
        request: CreateGenericComparisonRequest,
        idempotencyKey: String,
        requestSha256: String,
        principalId: String,
    ): GenericComparisonReceipt {
        requireCanonicalUuid(request.comparisonId, "comparisonId")
        if (!request.configurationSha256.matches(Regex("[0-9a-f]{64}"))) {
            throw ApiException.badRequest("INVALID_COMPARISON", "configurationSha256 is invalid.")
        }
        if (!request.officialIdentity.sha256.matches(Regex("[0-9a-f]{64}")) || request.officialIdentity.sizeBytes <= 0 ||
            request.officialIdentity.packageName.isBlank() || request.officialIdentity.versionName.isBlank() || request.officialIdentity.versionCode < 0
        ) throw ApiException.badRequest("INVALID_COMPARISON", "Official APK identity is incomplete.")
        val buildA = getStoredJob(request.buildAJobId)?.takeIf { it.principalId == principalId } ?: throw ApiException.notFound()
        val buildB = getStoredJob(request.buildBJobId)?.takeIf { it.principalId == principalId } ?: throw ApiException.notFound()
        val snapshotA = buildA.genericBuild ?: throw ApiException.conflict("COMPARISON_BUILD_INVALID", "Build A is not a generic build.")
        val snapshotB = buildB.genericBuild ?: throw ApiException.conflict("COMPARISON_BUILD_INVALID", "Build B is not a generic build.")
        val buildAFailed = buildA.state == JobState.FAILED
        val buildBFailed = buildB.state == JobState.FAILED
        if (buildA.state !in setOf(JobState.SUCCEEDED, JobState.FAILED) ||
            buildB.state !in setOf(JobState.SUCCEEDED, JobState.FAILED) ||
            snapshotA.attempt != GenericBuildAttempt.A || snapshotB.attempt != GenericBuildAttempt.B ||
            snapshotA.comparisonId != request.comparisonId || snapshotB.comparisonId != request.comparisonId ||
            snapshotA.configurationSha256 != request.configurationSha256 || snapshotB.configurationSha256 != request.configurationSha256 ||
            snapshotA.configurationCanonicalJson != snapshotB.configurationCanonicalJson ||
            snapshotA.retryOfJobId != null != (snapshotB.retryOfJobId != null) ||
            (buildAFailed && request.officialVsA != RawComparisonResult.INCOMPARABLE) ||
            (buildBFailed && request.officialVsB != RawComparisonResult.INCOMPARABLE) ||
            ((buildAFailed || buildBFailed) && request.buildAVsB != RawComparisonResult.INCOMPARABLE) ||
            ((buildAFailed || buildBFailed) && (request.trustEligible || request.installEligible))
        ) throw ApiException.conflict("COMPARISON_BUILD_MISMATCH", "Build A and B are not the persisted independent pair for this comparison.")
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT request_sha256,result_id FROM operations WHERE principal_id=? AND operation_kind='GENERIC_COMPARISON_CREATE' AND idempotency_key=?",
            ).use { statement ->
                statement.setString(1, principalId)
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { row ->
                    if (row.next()) {
                        if (row.getString(1) != requestSha256) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request.")
                        return GenericComparisonReceipt(genericComparison(row.getString(2), principalId), true)
                    }
                }
            }
        }
        val now = Instant.now(clock).toString()
        val retryParent = genericResourceRetryParent(request.comparisonId)
        val response = GenericComparisonResponse(
            comparisonId = request.comparisonId,
            configurationSha256 = request.configurationSha256,
            officialIdentity = request.officialIdentity,
            buildAJobId = request.buildAJobId,
            buildBJobId = request.buildBJobId,
            officialVsA = request.officialVsA,
            officialVsB = request.officialVsB,
            buildAVsB = request.buildAVsB,
            trustEligible = request.trustEligible,
            installEligible = request.installEligible,
            reproducible = buildA.state == JobState.SUCCEEDED && buildB.state == JobState.SUCCEEDED &&
                request.officialVsA == RawComparisonResult.MATCH &&
                request.officialVsB == RawComparisonResult.MATCH && request.buildAVsB == RawComparisonResult.MATCH &&
                request.trustEligible && request.installEligible,
            retryOfComparisonId = retryParent,
            resourceRetryCount = if (retryParent == null) 0 else 1,
            createdAt = now,
            updatedAt = now,
        )
        connection().use { connection ->
            connection.autoCommit = false
            try {
                requireActivePrincipal(connection, principalId)
                connection.prepareStatement(
                    "INSERT INTO generic_comparisons(comparison_id,principal_id,request_json,request_sha256,response_json,build_a_job_id,build_b_job_id,resource_retry_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                ).use { statement ->
                    statement.setString(1, request.comparisonId)
                    statement.setString(2, principalId)
                    statement.setString(3, GENERIC_JSON.encodeToString(request))
                    statement.setString(4, requestSha256)
                    statement.setString(5, GENERIC_JSON.encodeToString(response))
                    statement.setString(6, request.buildAJobId)
                    statement.setString(7, request.buildBJobId)
                    statement.setInt(8, response.resourceRetryCount)
                    statement.setString(9, now)
                    statement.setString(10, now)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO operations(operation_id,principal_id,operation_kind,idempotency_key,request_sha256,contract_id,contract_version,state,result_type,result_id,created_at,updated_at) VALUES(?,?,?,?,?,'apk-comparison',1,'COMPLETED','COMPARISON',?,?,?)",
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setString(2, principalId)
                    statement.setString(3, "GENERIC_COMPARISON_CREATE")
                    statement.setString(4, idempotencyKey)
                    statement.setString(5, requestSha256)
                    statement.setString(6, request.comparisonId)
                    statement.setString(7, now)
                    statement.setString(8, now)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                if (failure is java.sql.SQLException && failure.message.orEmpty().contains("UNIQUE")) {
                    throw ApiException.conflict("COMPARISON_ALREADY_EXISTS", "comparisonId already exists.")
                }
                throw failure
            }
        }
        return GenericComparisonReceipt(response, false)
    }

    @Synchronized
    internal fun genericComparison(comparisonId: String, principalId: String): GenericComparisonResponse = connection().use { connection ->
        connection.prepareStatement("SELECT response_json FROM generic_comparisons WHERE comparison_id=? AND principal_id=?").use { statement ->
            statement.setString(1, comparisonId)
            statement.setString(2, principalId)
            statement.executeQuery().use { row ->
                if (!row.next()) throw ApiException.notFound("COMPARISON_NOT_FOUND", "The requested comparison does not exist.")
                GENERIC_JSON.decodeFromString(row.getString(1))
            }
        }
    }

    @Synchronized
    internal fun createGenericResourceRetry(
        comparisonId: String,
        idempotencyKey: String,
        requestSha256: String,
        principalId: String,
    ): GenericResourceRetryReceipt {
        requireCanonicalUuid(comparisonId, "comparisonId")
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT request_sha256,result_id FROM operations WHERE principal_id=? AND operation_kind='GENERIC_RESOURCE_RETRY' AND idempotency_key=?",
            ).use { statement ->
                statement.setString(1, principalId)
                statement.setString(2, idempotencyKey)
                statement.executeQuery().use { row ->
                    if (row.next()) {
                        if (row.getString(1) != requestSha256) {
                            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request.")
                        }
                        val retryComparisonId = row.getString(2)
                            ?: throw ApiException.serviceUnavailable("RECONCILIATION_REQUIRED", "The accepted resource retry has no result identity.")
                        return GenericResourceRetryReceipt(genericResourceRetry(retryComparisonId), true)
                    }
                }
            }
        }

        val original = genericComparison(comparisonId, principalId)
        if (original.resourceRetryCount != 0 || original.retryOfComparisonId != null) {
            throw ApiException.conflict(
                "RESOURCE_RETRY_NOT_ELIGIBLE",
                "Only one resource retry is permitted for an original comparison.",
            )
        }
        val originalA = getStoredJob(original.buildAJobId) ?: throw ApiException.serviceUnavailable(
            "RECONCILIATION_REQUIRED",
            "The comparison references a missing Build A Job.",
        )
        val originalB = getStoredJob(original.buildBJobId) ?: throw ApiException.serviceUnavailable(
            "RECONCILIATION_REQUIRED",
            "The comparison references a missing Build B Job.",
        )
        val snapshotA = originalA.genericBuild ?: throw ApiException.conflict("RESOURCE_RETRY_NOT_ELIGIBLE", "Build A is not a generic build.")
        val snapshotB = originalB.genericBuild ?: throw ApiException.conflict("RESOURCE_RETRY_NOT_ELIGIBLE", "Build B is not a generic build.")
        val failures = listOf(originalA, originalB).mapNotNull { job ->
            if (job.state == JobState.FAILED) job.jobId to getJobError(job.jobId) else null
        }
        if (failures.none { it.second?.code == "SANDBOX_MEMORY_LIMIT_EXCEEDED" }) {
            throw ApiException.conflict(
                "RESOURCE_RETRY_NOT_ELIGIBLE",
                "A persisted cgroup memory-limit failure is required for a resource retry.",
            )
        }
        if (originalA.state !in setOf(JobState.SUCCEEDED, JobState.FAILED) ||
            originalB.state !in setOf(JobState.SUCCEEDED, JobState.FAILED) ||
            snapshotA.attempt != GenericBuildAttempt.A || snapshotB.attempt != GenericBuildAttempt.B ||
            snapshotA.comparisonId != comparisonId || snapshotB.comparisonId != comparisonId ||
            snapshotA.retryOfJobId != null || snapshotB.retryOfJobId != null ||
            snapshotA.configurationSha256 != snapshotB.configurationSha256 ||
            snapshotA.configurationCanonicalJson != snapshotB.configurationCanonicalJson
        ) {
            throw ApiException.conflict("RESOURCE_RETRY_NOT_ELIGIBLE", "The original comparison does not contain a retryable A/B pair.")
        }
        val configuration = GenericBuildContract.validate(originalA.repositoryUrl, originalA.revision.value, snapshotA)
        if (originalB.repositoryUrl != originalA.repositoryUrl || originalB.revision.value != originalA.revision.value) {
            throw ApiException.conflict("RESOURCE_RETRY_NOT_ELIGIBLE", "Build A and B do not share the same source snapshot.")
        }
        requireManagedToolchains(configuration)

        val retryComparisonId = UUID.randomUUID().toString()
        val retryA = snapshotA.copy(
            comparisonId = retryComparisonId,
            attempt = GenericBuildAttempt.A,
            retryOfJobId = originalA.jobId,
            memoryBytes = GenericDockerSandboxProfile.RETRY_MEMORY_BYTES,
        )
        val retryB = snapshotB.copy(
            comparisonId = retryComparisonId,
            attempt = GenericBuildAttempt.B,
            retryOfJobId = originalB.jobId,
            memoryBytes = GenericDockerSandboxProfile.RETRY_MEMORY_BYTES,
        )
        val retryAJobId = UUID.randomUUID().toString()
        val retryBJobId = UUID.randomUUID().toString()
        val now = Instant.now(clock).toString()
        val requestA = CreateJobRequest(
            executionMode = ExecutionMode.REAL_TRUSTED,
            repositoryUrl = originalA.repositoryUrl,
            revision = originalA.revision,
            genericBuild = retryA,
        )
        val requestB = requestA.copy(genericBuild = retryB)
        val response = GenericResourceRetryResponse(
            comparisonId = retryComparisonId,
            retryOfComparisonId = comparisonId,
            buildAJobId = retryAJobId,
            buildBJobId = retryBJobId,
            memoryBytes = GenericDockerSandboxProfile.RETRY_MEMORY_BYTES,
        )
        connection().use { connection ->
            connection.autoCommit = false
            try {
                insertJob(connection, retryAJobId, requestA, SandboxSnapshot.newGenericJob(retryA.memoryBytes), now, principalId)
                insertJob(connection, retryBJobId, requestB, SandboxSnapshot.newGenericJob(retryB.memoryBytes), now, principalId)
                insertGenericBuildRequest(connection, retryAJobId, retryA)
                insertGenericBuildRequest(connection, retryBJobId, retryB)
                connection.prepareStatement(
                    "INSERT INTO generic_resource_retries(retry_comparison_id,original_comparison_id,build_a_job_id,build_b_job_id,evidence_code,memory_bytes,created_at) VALUES(?,?,?,?,?,?,?)",
                ).use { statement ->
                    statement.setString(1, retryComparisonId)
                    statement.setString(2, comparisonId)
                    statement.setString(3, retryAJobId)
                    statement.setString(4, retryBJobId)
                    statement.setString(5, "SANDBOX_MEMORY_LIMIT_EXCEEDED")
                    statement.setLong(6, response.memoryBytes)
                    statement.setString(7, now)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO operations(operation_id,principal_id,operation_kind,idempotency_key,request_sha256,contract_id,contract_version,state,result_type,result_id,created_at,updated_at) VALUES(?,?,?,?,?,'apk-comparison',1,'COMPLETED','RESOURCE_RETRY',?,?,?)",
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setString(2, principalId)
                    statement.setString(3, "GENERIC_RESOURCE_RETRY")
                    statement.setString(4, idempotencyKey)
                    statement.setString(5, requestSha256)
                    statement.setString(6, retryComparisonId)
                    statement.setString(7, now)
                    statement.setString(8, now)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
        appendLog(retryAJobId, LogLevel.WARN, "Resource retry accepted from comparison $comparisonId with the 12 GiB generic memory profile.")
        appendLog(retryBJobId, LogLevel.WARN, "Resource retry accepted from comparison $comparisonId with the 12 GiB generic memory profile.")
        return GenericResourceRetryReceipt(response, false)
    }

    @Synchronized
    internal fun genericResourceRetry(retryComparisonId: String): GenericResourceRetryResponse = connection().use { connection ->
        connection.prepareStatement(
            "SELECT original_comparison_id,build_a_job_id,build_b_job_id,memory_bytes FROM generic_resource_retries WHERE retry_comparison_id=?",
        ).use { statement ->
            statement.setString(1, retryComparisonId)
            statement.executeQuery().use { row ->
                if (!row.next()) throw ApiException.serviceUnavailable(
                    "RECONCILIATION_REQUIRED",
                    "The accepted resource retry pair is missing its durable record.",
                )
                GenericResourceRetryResponse(
                    comparisonId = retryComparisonId,
                    retryOfComparisonId = row.getString("original_comparison_id"),
                    buildAJobId = row.getString("build_a_job_id"),
                    buildBJobId = row.getString("build_b_job_id"),
                    memoryBytes = row.getLong("memory_bytes"),
                )
            }
        }
    }

    private fun genericResourceRetryParent(retryComparisonId: String): String? = connection().use { connection ->
        connection.prepareStatement("SELECT original_comparison_id FROM generic_resource_retries WHERE retry_comparison_id=?").use { statement ->
            statement.setString(1, retryComparisonId)
            statement.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }
    }

    private fun getJobError(jobId: String): JobError? = connection().use { connection ->
        connection.prepareStatement("SELECT error_code,error_message FROM jobs WHERE job_id=?").use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { row ->
                if (!row.next()) null else row.getString("error_code")?.let { code -> JobError(code, row.getString("error_message")) }
            }
        }
    }

    private fun insertGenericBuildRequest(connection: Connection, jobId: String, snapshot: GenericBuildSnapshot) {
        connection.prepareStatement(
            "INSERT INTO generic_build_requests(job_id,request_json,configuration_sha256,comparison_id,attempt,retry_of_job_id,memory_bytes) VALUES(?,?,?,?,?,?,?)",
        ).use { statement ->
            statement.setString(1, jobId)
            statement.setString(2, GENERIC_JSON.encodeToString(snapshot))
            statement.setString(3, snapshot.configurationSha256)
            statement.setString(4, snapshot.comparisonId)
            statement.setString(5, snapshot.attempt.name)
            statement.setString(6, snapshot.retryOfJobId)
            statement.setLong(7, snapshot.memoryBytes)
            statement.executeUpdate()
        }
    }

    /** One-way upgrade before any external build, including an unfinished migrated HOST job. */
    @Synchronized
    internal fun requireCurrentManifest(jobId: String) {
        val snapshot = getStoredJob(jobId)?.sandbox ?: throw invalidSandboxSnapshot()
        snapshot.validateAnyProfile()
        sandboxTransaction { connection ->
            connection.prepareStatement("UPDATE jobs SET manifest_format = 'REQUIRED_V4' WHERE job_id = ?").use { statement ->
                statement.setString(1, jobId)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    @Synchronized
    internal fun sandboxOwnerId(): String = sandboxTransaction { connection ->
        connection.prepareStatement("INSERT OR IGNORE INTO sandbox_owner(singleton, owner_id) VALUES(1, ?)").use {
            it.setString(1, UUID.randomUUID().toString())
            it.executeUpdate()
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT owner_id FROM sandbox_owner WHERE singleton = 1").use {
                check(it.next())
                it.getString(1).also { id -> require(UUID.fromString(id).toString() == id) }
            }
        }
    }

    @Synchronized
    internal fun recordSandboxIntent(resource: SandboxResource) = sandboxTransaction { connection ->
        resource.validate()
        require(resource.containerId == null && !resource.removed && resource.observationJson == null)
        val snapshot = getStoredJob(resource.jobId)?.sandbox ?: throw invalidSandboxSnapshot()
        require(snapshot.jobSandbox.mode == BuildSandboxMode.DOCKER)
        require(getStoredJob(resource.jobId)?.state in setOf(JobState.VERIFYING_WRAPPER, JobState.DISCOVERING_CONFIGURATION))
        val endpoint = snapshot.validatedGenericProfile()?.endpoint ?: snapshot.validatedProfile()?.endpoint
        require(endpoint == resource.endpoint)
        connection.prepareStatement("SELECT owner_id FROM sandbox_owner WHERE singleton = 1").use { statement ->
            statement.executeQuery().use { require(it.next() && it.getString(1) == resource.ownerId) }
        }
        connection.prepareStatement(
            "INSERT INTO sandbox_resources(attempt_id, job_id, owner_id, role, expected_name, engine_id, endpoint) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setString(1, resource.attemptId)
            it.setString(2, resource.jobId)
            it.setString(3, resource.ownerId)
            it.setString(4, resource.role.name)
            it.setString(5, resource.expectedName)
            it.setString(6, resource.engineId)
            it.setString(7, resource.endpoint)
            check(it.executeUpdate() == 1)
        }
        connection.prepareStatement("UPDATE jobs SET sandbox_cleanup_status = 'PENDING' WHERE job_id = ?").use {
            it.setString(1, resource.jobId)
            check(it.executeUpdate() == 1)
        }
    }

    @Synchronized
    internal fun recordSandboxContainerId(attemptId: String, containerId: String) = sandboxTransaction { connection ->
        require(containerId.matches(Regex("[0-9a-f]{64}")))
        connection.prepareStatement(
            "UPDATE sandbox_resources SET container_id = ? WHERE attempt_id = ? AND removed = 0 " +
                "AND (container_id IS NULL OR container_id = ?)",
        ).use {
            it.setString(1, containerId)
            it.setString(2, attemptId)
            it.setString(3, containerId)
            check(it.executeUpdate() == 1)
        }
    }

    @Synchronized
    internal fun recordSandboxObservation(attemptId: String, observationJson: String) = sandboxTransaction { connection ->
        require(observationJson.toByteArray(Charsets.UTF_8).size <= 65_536)
        connection.prepareStatement(
            "UPDATE sandbox_resources SET observation_json = ? WHERE attempt_id = ? AND container_id IS NOT NULL AND removed = 0",
        ).use {
            it.setString(1, observationJson)
            it.setString(2, attemptId)
            check(it.executeUpdate() == 1)
        }
    }

    /** Caller must prove absence on the recorded engine first; no engine calls inside a transaction. */
    @Synchronized
    internal fun recordSandboxRemoved(attemptId: String) = sandboxTransaction { connection ->
        connection.prepareStatement("UPDATE sandbox_resources SET removed = 1, cleanup_failure_code = NULL WHERE attempt_id = ?").use {
            it.setString(1, attemptId)
            check(it.executeUpdate() == 1)
        }
        connection.prepareStatement(
            "UPDATE jobs SET sandbox_cleanup_status = 'COMPLETE' " +
                "WHERE job_id = (SELECT job_id FROM sandbox_resources WHERE attempt_id = ?) " +
                "AND NOT EXISTS (SELECT 1 FROM sandbox_resources r WHERE r.job_id = jobs.job_id AND r.removed = 0)",
        ).use {
            it.setString(1, attemptId)
            it.executeUpdate()
        }
    }

    @Synchronized
    internal fun recordSandboxFailure(attemptId: String, buildCode: String?, cleanupCode: String?) = sandboxTransaction { connection ->
        connection.prepareStatement(
            "UPDATE sandbox_resources SET build_failure_code = COALESCE(build_failure_code, ?), " +
                "cleanup_failure_code = COALESCE(?, cleanup_failure_code) WHERE attempt_id = ?",
        ).use {
            it.setString(1, buildCode)
            it.setString(2, cleanupCode)
            it.setString(3, attemptId)
            check(it.executeUpdate() == 1)
        }
    }

    @Synchronized
    internal fun sandboxResources(): List<SandboxResource> = sandboxTransaction { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM sandbox_resources ORDER BY rowid").use { rows ->
                buildList {
                    while (rows.next()) add(
                        SandboxResource(
                            attemptId = rows.getString("attempt_id"), jobId = rows.getString("job_id"),
                            ownerId = rows.getString("owner_id"), role = SandboxResourceRole.valueOf(rows.getString("role")),
                            expectedName = rows.getString("expected_name"), engineId = rows.getString("engine_id"),
                            endpoint = rows.getString("endpoint"), containerId = rows.getString("container_id"),
                            removed = rows.getInt("removed") == 1, observationJson = rows.getString("observation_json"),
                            buildFailureCode = rows.getString("build_failure_code"), cleanupFailureCode = rows.getString("cleanup_failure_code"),
                        ).also { it.validate() },
                    )
                }
            }
        }
    }

    private fun <T> sandboxTransaction(block: (Connection) -> T): T = try {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    } catch (failure: TrustedBuildFailure) {
        throw failure
    } catch (_: Exception) {
        throw TrustedBuildFailure("SANDBOX_AUDIT_PERSISTENCE_FAILED", "The sandbox audit could not be persisted or restored.")
    }

    @Synchronized
    internal fun updateState(
        jobId: String,
        state: JobState,
        progressPercent: Int,
        error: JobError? = null,
    ): Boolean = connection().use { connection ->
        connection.prepareStatement(
            """
            UPDATE jobs
            SET state = ?, progress_percent = ?, error_code = ?, error_message = ?, updated_at = ?
            WHERE job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, state.name)
            statement.setInt(2, progressPercent.coerceIn(0, 100))
            statement.setString(3, error?.code)
            statement.setString(4, error?.message)
            statement.setString(5, Instant.now(clock).toString())
            statement.setString(6, jobId)
            statement.executeUpdate() == 1
        }
    }

    @Synchronized
    fun markQueuedIfCreated(jobId: String): Boolean = connection().use { connection ->
        connection.prepareStatement(
            """
            UPDATE jobs
            SET state = ?, progress_percent = 5, updated_at = ?
            WHERE job_id = ? AND state = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, JobState.QUEUED.name)
            statement.setString(2, Instant.now(clock).toString())
            statement.setString(3, jobId)
            statement.setString(4, JobState.CREATED.name)
            val queued = statement.executeUpdate() == 1
            if (queued) appendLog(jobId, LogLevel.INFO, "Job entered the simulated execution queue.")
            queued
        }
    }

    @Synchronized
    internal fun beginResolvingRealJob(jobId: String, recipe: BuildRecipe): Boolean {
        val current = getStateAndProgress(jobId) ?: return false
        if (current.state != JobState.CREATED) return false
        appendLog(jobId, LogLevel.INFO, "Resolving the allowlisted ref with recipe ${recipe.id}.")
        return connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE jobs
                SET state = ?, progress_percent = 5, effective_recipe_id = ?,
                    effective_variant_name = ?, effective_build_root = ?, effective_java_major = ?,
                    effective_build_tasks = ?, effective_dependency_pinning = ?,
                    effective_source_date_epoch = ?, effective_no_build_cache = ?,
                    effective_fixed_locale = ?, updated_at = ?
                WHERE job_id = ? AND state = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.RESOLVING_SOURCE.name)
                statement.setString(2, recipe.id)
                statement.setString(3, recipe.variantName)
                statement.setString(4, recipe.buildRoot)
                statement.setInt(5, recipe.javaMajor)
                statement.setString(6, recipe.tasks.joinToString("\n"))
                statement.setString(7, recipe.dependencyPinning.name)
                recipe.determinism.sourceDateEpoch?.let { statement.setLong(8, it) }
                    ?: statement.setNull(8, java.sql.Types.BIGINT)
                statement.setInt(9, if (recipe.determinism.noBuildCache) 1 else 0)
                statement.setString(10, recipe.determinism.fixedLocale?.name)
                statement.setString(11, Instant.now(clock).toString())
                statement.setString(12, jobId)
                statement.setString(13, JobState.CREATED.name)
                statement.executeUpdate() == 1
            }
        }
    }

    @Synchronized
    internal fun recordDependencyLockPreBuild(jobId: String, sha256: String): Boolean =
        recordDependencyLockHash(jobId, "dependency_lock_pre_sha256", sha256)

    @Synchronized
    internal fun recordDependencyLockPostBuild(jobId: String, sha256: String): Boolean =
        recordDependencyLockHash(jobId, "dependency_lock_post_sha256", sha256)

    private fun recordDependencyLockHash(jobId: String, column: String, sha256: String): Boolean {
        require(column in setOf("dependency_lock_pre_sha256", "dependency_lock_post_sha256"))
        return connection().use { connection ->
            connection.prepareStatement(
                "UPDATE jobs SET $column = ?, updated_at = ? WHERE job_id = ?",
            ).use { statement ->
                statement.setString(1, sha256)
                statement.setString(2, Instant.now(clock).toString())
                statement.setString(3, jobId)
                statement.executeUpdate() == 1
            }
        }
    }

    @Synchronized
    fun awaitRealConfirmation(jobId: String, resolvedCommitSha: String): Boolean {
        val current = getStateAndProgress(jobId) ?: return false
        if (current.state.isTerminal) return false
        appendLog(
            jobId,
            LogLevel.WARN,
            "Host execution is paused until the client confirms commit $resolvedCommitSha and acknowledges RCE risk.",
        )
        return connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE jobs
                SET resolved_commit_sha = ?, state = ?, progress_percent = 10,
                    requires_confirmation = 1, updated_at = ?
                WHERE job_id = ? AND state = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, resolvedCommitSha)
                statement.setString(2, JobState.AWAITING_CONFIRMATION.name)
                statement.setString(3, Instant.now(clock).toString())
                statement.setString(4, jobId)
                statement.setString(5, JobState.RESOLVING_SOURCE.name)
                statement.executeUpdate() == 1
            }
        }
    }

    @Synchronized
    fun confirmRealJob(jobId: String, resolvedCommitSha: String, principalId: String = LOCAL_DEVELOPMENT_PRINCIPAL): Boolean {
        val current = getStoredJob(jobId) ?: return false
        if (current.principalId != principalId) throw ApiException.notFound()
        if (current.state != JobState.AWAITING_CONFIRMATION || current.resolvedCommitSha != resolvedCommitSha) return false
        val accepted = connection().use { connection ->
            connection.autoCommit = false
            try {
            requireActivePrincipal(connection, principalId)
            connection.prepareStatement(
                """
                UPDATE jobs
                SET state = ?, progress_percent = 15, requires_confirmation = 0, updated_at = ?
                WHERE job_id = ? AND state = ? AND resolved_commit_sha = ? AND principal_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.QUEUED.name)
                statement.setString(2, Instant.now(clock).toString())
                statement.setString(3, jobId)
                statement.setString(4, JobState.AWAITING_CONFIRMATION.name)
                statement.setString(5, resolvedCommitSha)
                statement.setString(6, principalId)
                (statement.executeUpdate() == 1).also { connection.commit() }
            }
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        if (accepted) appendLog(jobId, LogLevel.WARN, "Client acknowledged RCE risk for commit $resolvedCommitSha.")
        return accepted
    }

    @Synchronized
    internal fun recordWrapperVerification(jobId: String, verification: WrapperVerification): Boolean =
        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE jobs
                SET gradle_version = ?, distribution_url = ?, distribution_sha256 = ?,
                    distribution_checksum_source = ?, wrapper_jar_gradle_version = ?,
                    wrapper_jar_sha256 = ?, updated_at = ?
                WHERE job_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, verification.gradleVersion)
                statement.setString(2, verification.distributionUrl)
                statement.setString(3, verification.distributionSha256)
                statement.setString(4, verification.distributionChecksumSource)
                statement.setString(5, verification.wrapperJarGradleVersion)
                statement.setString(6, verification.wrapperJarSha256)
                statement.setString(7, Instant.now(clock).toString())
                statement.setString(8, jobId)
                statement.executeUpdate() == 1
            }
        }

    @Synchronized
    internal fun recordBuildManifest(jobId: String, relativePath: String, sha256: String): Boolean =
        connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE jobs
                SET manifest_path = ?, manifest_sha256 = ?, updated_at = ?
                WHERE job_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, relativePath)
                statement.setString(2, sha256)
                statement.setString(3, Instant.now(clock).toString())
                statement.setString(4, jobId)
                statement.executeUpdate() == 1
            }
        }

    @Synchronized
    internal fun getBuildManifestAudit(jobId: String): StoredBuildManifestAudit? = connection().use { connection ->
        connection.prepareStatement(
            "SELECT manifest_path, manifest_sha256 FROM jobs WHERE job_id = ?",
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val relativePath = result.getString("manifest_path") ?: return@use null
                val sha256 = result.getString("manifest_sha256") ?: return@use null
                StoredBuildManifestAudit(relativePath, sha256)
            }
        }
    }

    @Synchronized
    internal fun recordSourceScanResult(result: SourceScanResult): Boolean = connection().use { connection ->
        connection.autoCommit = false
        try {
            val jobId = result.detail.jobId
            val currentState = connection.prepareStatement("SELECT state FROM jobs WHERE job_id = ?").use { statement ->
                statement.setString(1, jobId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    JobState.valueOf(rows.getString(1))
                }
            }
            if (currentState != JobState.SCANNING_SOURCE) {
                connection.rollback()
                return@use false
            }
            connection.prepareStatement("DELETE FROM source_scan_results WHERE job_id = ?").use { statement ->
                statement.setString(1, jobId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO source_scan_results (
                    job_id, schema_version, resolved_commit_sha, scanner_version, result_sha256,
                    canonical_json, scanned_files, scanned_bytes, skipped_binary_files,
                    skipped_symlinks, finding_count, requires_review, reviewed
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jobId)
                statement.setInt(2, result.detail.schemaVersion)
                statement.setString(3, result.detail.resolvedCommitSha)
                statement.setString(4, result.detail.scannerVersion)
                statement.setString(5, result.detail.resultSha256)
                statement.setBytes(6, result.canonicalBytes)
                statement.setInt(7, result.detail.summary.scannedFiles)
                statement.setLong(8, result.detail.summary.scannedBytes)
                statement.setInt(9, result.detail.summary.skippedBinaryFiles)
                statement.setInt(10, result.detail.summary.skippedSymlinks)
                statement.setInt(11, result.detail.summary.findingCount)
                statement.setInt(12, if (result.requiresReview) 1 else 0)
                statement.executeUpdate()
            }
            result.detail.detectorCounts.forEach { count ->
                connection.prepareStatement(
                    "INSERT INTO source_scan_detector_counts (job_id, detector_id, finding_count) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, jobId)
                    statement.setString(2, count.detectorId.name)
                    statement.setInt(3, count.count)
                    statement.executeUpdate()
                }
            }
            result.detail.findings.forEachIndexed { ordinal, finding ->
                connection.prepareStatement(
                    """
                    INSERT INTO source_scan_findings (job_id, ordinal, detector_id, display_path, line, column_number)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, jobId)
                    statement.setInt(2, ordinal)
                    statement.setString(3, finding.detectorId.name)
                    statement.setString(4, finding.displayPath)
                    finding.line?.let { statement.setInt(5, it) } ?: statement.setNull(5, java.sql.Types.INTEGER)
                    finding.column?.let { statement.setInt(6, it) } ?: statement.setNull(6, java.sql.Types.INTEGER)
                    statement.executeUpdate()
                }
            }
            val nextState = if (result.requiresReview) JobState.AWAITING_SCAN_REVIEW else JobState.VERIFYING_WRAPPER
            val nextProgress = if (result.requiresReview) 35 else 40
            val updated = connection.prepareStatement(
                """
                UPDATE jobs SET state = ?, progress_percent = ?, updated_at = ?
                WHERE job_id = ? AND state = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, nextState.name)
                statement.setInt(2, nextProgress)
                statement.setString(3, Instant.now(clock).toString())
                statement.setString(4, jobId)
                statement.setString(5, JobState.SCANNING_SOURCE.name)
                statement.executeUpdate() == 1
            }
            if (!updated) {
                connection.rollback()
                return@use false
            }
            connection.commit()
            true
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    @Synchronized
    internal fun getStoredSourceScan(jobId: String): StoredSourceScan? = connection().use { connection ->
        loadSourceScan(connection, jobId)
    }

    @Synchronized
    fun getSourceScan(jobId: String): SourceScanDetailResponse? = getStoredSourceScan(jobId)?.detail

    @Synchronized
    internal fun reviewSourceScan(jobId: String, resultSha256: String, principalId: String = LOCAL_DEVELOPMENT_PRINCIPAL): ReviewSourceScanResult = connection().use { connection ->
        connection.autoCommit = false
        try {
            requireActivePrincipal(connection, principalId)
            val row = connection.prepareStatement(
                """
                SELECT j.state, s.result_sha256, s.requires_review, s.reviewed
                FROM jobs j LEFT JOIN source_scan_results s ON s.job_id = j.job_id
                WHERE j.job_id = ? AND j.principal_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jobId)
                statement.setString(2, principalId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    ReviewRow(
                        state = JobState.valueOf(rows.getString("state")),
                        resultSha256 = rows.getString("result_sha256"),
                        requiresReview = rows.getInt("requires_review") != 0,
                        reviewed = rows.getInt("reviewed") != 0,
                    )
                }
            } ?: run {
                connection.rollback()
                return@use ReviewSourceScanResult.NOT_FOUND
            }
            if (row.state != JobState.AWAITING_SCAN_REVIEW || row.resultSha256 == null || !row.requiresReview || row.reviewed) {
                connection.rollback()
                return@use ReviewSourceScanResult.NOT_REQUIRED
            }
            if (row.resultSha256 != resultSha256) {
                connection.rollback()
                return@use ReviewSourceScanResult.DIGEST_MISMATCH
            }
            val now = Instant.now(clock).toString()
            val reviewed = connection.prepareStatement(
                """
                UPDATE source_scan_results SET reviewed = 1, reviewed_digest = ?, reviewed_at = ?
                WHERE job_id = ? AND reviewed = 0 AND result_sha256 = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, resultSha256)
                statement.setString(2, now)
                statement.setString(3, jobId)
                statement.setString(4, resultSha256)
                statement.executeUpdate() == 1
            }
            val queued = reviewed && connection.prepareStatement(
                """
                UPDATE jobs SET state = ?, progress_percent = 38, updated_at = ?
                WHERE job_id = ? AND state = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.QUEUED.name)
                statement.setString(2, now)
                statement.setString(3, jobId)
                statement.setString(4, JobState.AWAITING_SCAN_REVIEW.name)
                statement.executeUpdate() == 1
            }
            if (!queued) {
                connection.rollback()
                return@use ReviewSourceScanResult.RACE
            }
            connection.commit()
            ReviewSourceScanResult.SUCCESS
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    @Synchronized
    internal fun listResumableSourceScanJobIds(): List<String> = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT j.job_id FROM jobs j
            JOIN source_scan_results s ON s.job_id = j.job_id
            WHERE j.state = ? AND s.requires_review = 1 AND s.reviewed = 1
            ORDER BY j.created_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, JobState.QUEUED.name)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    @Synchronized
    fun transitionIfActive(
        jobId: String,
        state: JobState,
        progressPercent: Int,
        message: String,
    ): Boolean {
        val current = getStateAndProgress(jobId) ?: return false
        if (current.state.isTerminal) return false
        appendLog(jobId, LogLevel.INFO, message)
        return updateState(jobId, state, progressPercent)
    }

    @Synchronized
    fun completeSimulatedSuccessIfActive(jobId: String, artifact: ArtifactMetadata): Boolean {
        val current = getStateAndProgress(jobId) ?: return false
        if (current.state.isTerminal) return false
        addArtifact(jobId, artifact)
        appendLog(jobId, LogLevel.INFO, "Simulated APK metadata registered.")
        appendLog(jobId, LogLevel.INFO, "Simulated build succeeded.")
        return updateState(jobId, JobState.SUCCEEDED, 100)
    }

    @Synchronized
    internal fun completeRealSuccessIfActive(jobId: String, artifacts: List<StoredArtifact>): Boolean {
        val current = getStateAndProgress(jobId) ?: return false
        if (current.state.isTerminal) return false
        if (getStoredJob(jobId)?.sandbox?.jobSandbox?.mode == BuildSandboxMode.DOCKER) {
            require(getStoredJob(jobId)?.sandbox?.jobSandbox?.cleanupStatus == SandboxCleanupStatus.COMPLETE)
            val resources = sandboxResources().filter { it.jobId == jobId }
            require(resources.size == 2 && resources.all { it.removed })
            require(resources.map { it.role }.toSet() == SandboxResourceRole.entries.toSet())
        }
        artifacts.forEach { addArtifact(jobId, it) }
        appendLog(jobId, LogLevel.INFO, "Trusted real build succeeded; ${artifacts.size} APK artifact(s) registered.")
        return updateState(jobId, JobState.SUCCEEDED, 100)
    }

    @Synchronized
    fun failIfActive(
        jobId: String,
        error: JobError,
        logMessage: String,
        progressPercent: Int? = null,
    ): Boolean {
        val current = getStateAndProgress(jobId) ?: return false
        if (current.state.isTerminal) return false
        deleteArtifacts(jobId)
        appendLog(jobId, LogLevel.ERROR, logMessage)
        clearConfirmation(jobId)
        return updateState(
            jobId = jobId,
            state = JobState.FAILED,
            progressPercent = progressPercent ?: current.progressPercent,
            error = error,
        )
    }

    @Synchronized
    internal fun cancelIfActive(jobId: String): CancelJobResult {
        val current = getStateAndProgress(jobId) ?: return CancelJobResult.NOT_FOUND
        if (current.state.isTerminal) return CancelJobResult.ALREADY_TERMINAL
        deleteArtifacts(jobId)
        appendLog(jobId, LogLevel.WARN, "Job cancelled by the client.")
        clearConfirmation(jobId)
        updateState(jobId, JobState.CANCELLED, current.progressPercent)
        return CancelJobResult.CANCELLED
    }

    private fun addArtifact(jobId: String, artifact: ArtifactMetadata) {
        addArtifact(jobId, StoredArtifact(artifact, contentRelativePath = null))
    }

    private fun addArtifact(jobId: String, artifact: StoredArtifact) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO artifacts (
                    artifact_id, job_id, file_name, size_bytes, sha256,
                    package_name, version_name, version_code, content_path
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, artifact.metadata.artifactId)
                statement.setString(2, jobId)
                statement.setString(3, artifact.metadata.fileName)
                statement.setLong(4, artifact.metadata.sizeBytes)
                statement.setString(5, artifact.metadata.sha256)
                statement.setString(6, artifact.metadata.packageName)
                statement.setString(7, artifact.metadata.versionName)
                statement.setLong(8, artifact.metadata.versionCode)
                statement.setString(9, artifact.contentRelativePath)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    fun listArtifacts(jobId: String): List<ArtifactMetadata>? {
        connection().use { connection ->
            if (!jobExists(connection, jobId)) return null
            return listArtifacts(connection, jobId)
        }
    }

    @Synchronized
    internal fun getStoredArtifact(jobId: String, artifactId: String): StoredArtifact? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT artifact_id, file_name, size_bytes, sha256, package_name, version_name,
                   version_code, content_path
            FROM artifacts
            WHERE job_id = ? AND artifact_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.setString(2, artifactId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                StoredArtifact(
                    metadata = result.toArtifactMetadata(),
                    contentRelativePath = result.getString("content_path"),
                )
            }
        }
    }

    @Synchronized
    fun appendLog(jobId: String, level: LogLevel, message: String): LogEntry {
        val timestamp = Instant.now(clock).toString()
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        val logPath = logPath(jobId)

        connection(SQLiteConfig.TransactionMode.IMMEDIATE).use { connection ->
            connection.autoCommit = false
            try {
                val sequence = latestLogSequence(connection, jobId) + 1
                val offset: Long
                openPrivateJobLog(logPath, setOf(CREATE, READ, WRITE)).use { channel ->
                    offset = channel.size()
                    channel.position(offset)
                    writeFully(channel, ByteBuffer.wrap(messageBytes))
                    channel.force(true)
                }
                connection.prepareStatement(
                    """
                    INSERT INTO log_entries (
                        job_id, sequence, timestamp, level, file_offset, byte_length
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, jobId)
                    statement.setLong(2, sequence)
                    statement.setString(3, timestamp)
                    statement.setString(4, level.name)
                    statement.setLong(5, offset)
                    statement.setInt(6, messageBytes.size)
                    statement.executeUpdate()
                }
                connection.commit()
                return LogEntry(sequence, timestamp, level, message)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    @Synchronized
    fun getLogs(jobId: String, afterSequence: Long, limit: Int): LogResponse? {
        connection().use { connection ->
            if (!jobExists(connection, jobId)) return null
            val indexedEntries = mutableListOf<IndexedLogEntry>()
            connection.prepareStatement(
                """
                SELECT sequence, timestamp, level, file_offset, byte_length
                FROM log_entries
                WHERE job_id = ? AND sequence > ?
                ORDER BY sequence
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jobId)
                statement.setLong(2, afterSequence)
                statement.setInt(3, limit)
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        indexedEntries += IndexedLogEntry(
                            sequence = result.getLong("sequence"),
                            timestamp = result.getString("timestamp"),
                            level = LogLevel.valueOf(result.getString("level")),
                            offset = result.getLong("file_offset"),
                            byteLength = result.getInt("byte_length"),
                        )
                    }
                }
            }

            val logPath = logPath(jobId)
            val entries = if (indexedEntries.isEmpty()) {
                emptyList()
            } else {
                openPrivateJobLog(logPath, setOf(READ)).use { channel ->
                    indexedEntries.map { indexed ->
                        check(indexed.offset >= 0 && indexed.byteLength >= 0) { "JOB_LOG_INDEX_INVALID" }
                        check(indexed.offset <= channel.size() - indexed.byteLength) { "JOB_LOG_INDEX_OUT_OF_RANGE" }
                        val bytes = ByteArray(indexed.byteLength)
                        channel.position(indexed.offset)
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "JOB_LOG_READ_INCOMPLETE" }
                        LogEntry(
                            sequence = indexed.sequence,
                            timestamp = indexed.timestamp,
                            level = indexed.level,
                            message = decodeUtf8(bytes),
                        )
                    }
                }
            }
            val nextSequence = entries.lastOrNull()?.sequence ?: afterSequence
            val hasMore = latestLogSequence(connection, jobId) > nextSequence
            return LogResponse(entries, nextSequence, hasMore)
        }
    }

    @Synchronized
    fun markRunningJobsInterrupted(clearArtifactMetadata: Boolean = true): List<String> {
        val interruptibleStates = setOf(
            JobState.CREATED,
            JobState.RESOLVING_SOURCE,
            JobState.QUEUED,
            JobState.CLONING,
            JobState.SCANNING_SOURCE,
            JobState.VERIFYING_WRAPPER,
            JobState.DISCOVERING_CONFIGURATION,
            JobState.BUILDING,
            JobState.DISCOVERING_ARTIFACTS,
        )
        val resumable = listResumableSourceScanJobIds().toSet()
        val interrupted = connection().use { connection ->
            val placeholders = interruptibleStates.joinToString(",") { "?" }
            connection.prepareStatement(
                "SELECT job_id FROM jobs WHERE state IN ($placeholders)",
            ).use { statement ->
                interruptibleStates.forEachIndexed { index, state ->
                    statement.setString(index + 1, state.name)
                }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            result.getString("job_id").takeUnless(resumable::contains)?.let(::add)
                        }
                    }
                }
            }
        }
        interrupted.forEach { jobId ->
            if (clearArtifactMetadata) deleteArtifacts(jobId)
            appendLog(jobId, LogLevel.WARN, "Job marked INTERRUPTED during Runner startup.")
            updateState(
                jobId = jobId,
                state = JobState.INTERRUPTED,
                progressPercent = getStateAndProgress(jobId)?.progressPercent ?: 0,
                error = JobError(
                    code = "RUNNER_RESTARTED",
                    message = "The Runner stopped before this job completed.",
                ),
            )
        }
        return interrupted
    }

    @Synchronized
    fun isReady(): Boolean = runCatching {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result -> result.next() }
            }
        }
    }.getOrDefault(false)

    private fun initializeSchema(connection: Connection) {
        connection.run {
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    val schemaVersion = statement.executeQuery("PRAGMA user_version").use { result ->
                        result.next()
                        result.getInt(1)
                    }
                    require(schemaVersion in 0..SCHEMA_VERSION) {
                        "Unsupported Runner database schema version: $schemaVersion"
                    }
                    if (schemaVersion >= 9) {
                        statement.executeQuery("SELECT singleton,runner_id FROM runner_identity").use { identity ->
                            check(identity.next()) { "RUNNER_IDENTITY_MISSING: established Runner identity is missing." }
                            val id = identity.getString("runner_id")
                            check(identity.getInt("singleton") == 1 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && !identity.next()) {
                                "RUNNER_IDENTITY_INVALID: established Runner identity is not singular and canonical."
                            }
                        }
                    }
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS jobs (
                            job_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL DEFAULT 'local-development',
                            execution_mode TEXT NOT NULL,
                            repository_url TEXT NOT NULL,
                            revision_type TEXT NOT NULL,
                            revision_value TEXT NOT NULL,
                            simulation_outcome TEXT,
                            resolved_commit_sha TEXT,
                            state TEXT NOT NULL,
                            progress_percent INTEGER NOT NULL,
                            requires_confirmation INTEGER NOT NULL,
                            effective_recipe_id TEXT,
                            effective_variant_name TEXT,
                            effective_build_root TEXT,
                            effective_java_major INTEGER,
                            effective_build_tasks TEXT,
                            effective_dependency_pinning TEXT NOT NULL DEFAULT 'NONE',
                            effective_source_date_epoch INTEGER,
                            effective_no_build_cache INTEGER NOT NULL DEFAULT 0,
                            effective_fixed_locale TEXT,
                            dependency_lock_pre_sha256 TEXT,
                            dependency_lock_post_sha256 TEXT,
                            error_code TEXT,
                            error_message TEXT,
                            gradle_version TEXT,
                            distribution_url TEXT,
                            distribution_sha256 TEXT,
                            distribution_checksum_source TEXT,
                            wrapper_jar_gradle_version TEXT,
                            wrapper_jar_sha256 TEXT,
                            manifest_path TEXT,
                            manifest_sha256 TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    if (schemaVersion < 12 && !columnExists(connection, "jobs", "principal_id")) {
                        statement.executeUpdate("ALTER TABLE jobs ADD COLUMN principal_id TEXT NOT NULL DEFAULT 'local-development'")
                    }
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS source_scan_results (
                            job_id TEXT PRIMARY KEY REFERENCES jobs(job_id) ON DELETE CASCADE,
                            schema_version INTEGER NOT NULL,
                            resolved_commit_sha TEXT NOT NULL,
                            scanner_version TEXT NOT NULL,
                            result_sha256 TEXT NOT NULL,
                            canonical_json BLOB NOT NULL,
                            scanned_files INTEGER NOT NULL,
                            scanned_bytes INTEGER NOT NULL,
                            skipped_binary_files INTEGER NOT NULL,
                            skipped_symlinks INTEGER NOT NULL,
                            finding_count INTEGER NOT NULL,
                            requires_review INTEGER NOT NULL,
                            reviewed INTEGER NOT NULL,
                            reviewed_digest TEXT,
                            reviewed_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS source_scan_detector_counts (
                            job_id TEXT NOT NULL REFERENCES source_scan_results(job_id) ON DELETE CASCADE,
                            detector_id TEXT NOT NULL,
                            finding_count INTEGER NOT NULL,
                            PRIMARY KEY (job_id, detector_id)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS source_scan_findings (
                            job_id TEXT NOT NULL REFERENCES source_scan_results(job_id) ON DELETE CASCADE,
                            ordinal INTEGER NOT NULL,
                            detector_id TEXT NOT NULL,
                            display_path TEXT NOT NULL,
                            line INTEGER,
                            column_number INTEGER,
                            PRIMARY KEY (job_id, ordinal)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS artifacts (
                            artifact_id TEXT PRIMARY KEY,
                            job_id TEXT NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
                            file_name TEXT NOT NULL,
                            size_bytes INTEGER NOT NULL,
                            sha256 TEXT NOT NULL,
                            package_name TEXT NOT NULL,
                            version_name TEXT NOT NULL,
                            version_code INTEGER NOT NULL,
                            content_path TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS log_entries (
                            job_id TEXT NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
                            sequence INTEGER NOT NULL,
                            timestamp TEXT NOT NULL,
                            level TEXT NOT NULL,
                            file_offset INTEGER NOT NULL,
                            byte_length INTEGER NOT NULL,
                            PRIMARY KEY (job_id, sequence)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS jobs_state_index ON jobs(state)",
                    )
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS jobs_principal_index ON jobs(principal_id)",
                    )
                    if (schemaVersion == 1) {
                        AUDIT_COLUMNS.forEach { columnDefinition ->
                            statement.executeUpdate("ALTER TABLE jobs ADD COLUMN $columnDefinition")
                        }
                    }
                    if (schemaVersion in 1..2 && !columnExists(connection, "artifacts", "content_path")) {
                        statement.executeUpdate("ALTER TABLE artifacts ADD COLUMN content_path TEXT")
                    }
                    if (schemaVersion in 1..3) {
                        BUILD_PROFILE_COLUMNS.forEach { columnDefinition ->
                            if (!columnExists(connection, "jobs", columnDefinition.substringBefore(' '))) {
                                statement.executeUpdate("ALTER TABLE jobs ADD COLUMN $columnDefinition")
                            }
                        }
                    }
                    if (schemaVersion in 1..4) {
                        PINNING_COLUMNS.forEach { columnDefinition ->
                            if (!columnExists(connection, "jobs", columnDefinition.substringBefore(' '))) {
                                statement.executeUpdate("ALTER TABLE jobs ADD COLUMN $columnDefinition")
                            }
                        }
                    }
                    if (schemaVersion in 1..5) {
                        DETERMINISM_COLUMNS.forEach { columnDefinition ->
                            if (!columnExists(connection, "jobs", columnDefinition.substringBefore(' '))) {
                                statement.executeUpdate("ALTER TABLE jobs ADD COLUMN $columnDefinition")
                            }
                        }
                    }
                    if (schemaVersion < 8) {
                        SANDBOX_COLUMNS.forEach { definition ->
                            if (!columnExists(connection, "jobs", definition.substringBefore(' '))) {
                                statement.executeUpdate("ALTER TABLE jobs ADD COLUMN $definition")
                            }
                        }
                        // Do not overwrite a valid snapshot in an already-upgraded fixture or restored database.
                        statement.executeUpdate(
                            "UPDATE jobs SET sandbox_mode = 'HOST', sandbox_origin = 'LEGACY_HOST', " +
                                "manifest_format = 'LEGACY_ALLOWED' WHERE execution_mode = 'REAL_TRUSTED' AND sandbox_mode IS NULL",
                        )
                    }
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS sandbox_owner (
                            singleton INTEGER PRIMARY KEY CHECK(singleton = 1), owner_id TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS sandbox_resources (
                            attempt_id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(job_id),
                            owner_id TEXT NOT NULL, role TEXT NOT NULL, expected_name TEXT NOT NULL UNIQUE,
                            engine_id TEXT NOT NULL, endpoint TEXT NOT NULL, container_id TEXT,
                            removed INTEGER NOT NULL DEFAULT 0, observation_json TEXT,
                            build_failure_code TEXT, cleanup_failure_code TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS runner_identity (
                            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                            runner_id TEXT NOT NULL UNIQUE,
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    if (schemaVersion < 9) {
                        connection.prepareStatement(
                            "INSERT INTO runner_identity(singleton,runner_id,created_at) SELECT 1,?,? WHERE NOT EXISTS(SELECT 1 FROM runner_identity)",
                        ).use { identity ->
                            identity.setString(1, UUID.randomUUID().toString())
                            identity.setString(2, Instant.now(clock).toString())
                            identity.executeUpdate()
                        }
                    }
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS storage_settings (
                            area TEXT PRIMARY KEY,
                            budget_bytes INTEGER NOT NULL CHECK(budget_bytes > 0),
                            warning_percent INTEGER NOT NULL CHECK(warning_percent BETWEEN 0 AND 100),
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS operations (
                            operation_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL,
                            operation_kind TEXT NOT NULL,
                            idempotency_key TEXT NOT NULL,
                            request_sha256 TEXT NOT NULL,
                            contract_id TEXT NOT NULL,
                            contract_version INTEGER NOT NULL,
                            state TEXT NOT NULL,
                            result_type TEXT,
                            result_id TEXT,
                            result_json TEXT,
                            reason_code TEXT,
                            reason_message TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            UNIQUE(principal_id, operation_kind, idempotency_key)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS retention_holds (
                            hold_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL,
                            resource_kind TEXT NOT NULL,
                            resource_id TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            client_reference_type TEXT NOT NULL,
                            client_reference_id TEXT NOT NULL,
                            state TEXT NOT NULL,
                            created_operation_id TEXT NOT NULL REFERENCES operations(operation_id),
                            released_operation_id TEXT REFERENCES operations(operation_id),
                            created_at TEXT NOT NULL,
                            released_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS retention_holds_active_identity
                        ON retention_holds(
                            principal_id, resource_kind, resource_id, reason,
                            client_reference_type, client_reference_id
                        ) WHERE state = 'ACTIVE'
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS storage_reservations (
                            reservation_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL,
                            area TEXT NOT NULL,
                            purpose TEXT NOT NULL,
                            resource_kind TEXT NOT NULL,
                            resource_id TEXT NOT NULL,
                            requested_bytes INTEGER NOT NULL CHECK(requested_bytes > 0),
                            state TEXT NOT NULL,
                            created_operation_id TEXT NOT NULL REFERENCES operations(operation_id),
                            released_operation_id TEXT REFERENCES operations(operation_id),
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS storage_reservations_active_identity
                        ON storage_reservations(principal_id, area, purpose, resource_kind, resource_id)
                        WHERE state = 'ACTIVE'
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS resource_availability (
                            resource_kind TEXT NOT NULL,
                            resource_id TEXT NOT NULL,
                            state TEXT NOT NULL,
                            observed_bytes INTEGER,
                            known_sha256 TEXT,
                            last_used_at TEXT,
                            checked_at TEXT NOT NULL,
                            deletion_run_id TEXT,
                            deletion_reason TEXT,
                            PRIMARY KEY(resource_kind, resource_id)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS cleanup_runs (
                            cleanup_run_id TEXT PRIMARY KEY,
                            preview_id TEXT NOT NULL UNIQUE,
                            principal_id TEXT NOT NULL,
                            area TEXT NOT NULL,
                            filter_sha256 TEXT NOT NULL,
                            state TEXT NOT NULL,
                            truncated INTEGER NOT NULL,
                            expires_at TEXT NOT NULL,
                            released_bytes INTEGER NOT NULL DEFAULT 0,
                            created_operation_id TEXT NOT NULL REFERENCES operations(operation_id),
                            execute_operation_id TEXT REFERENCES operations(operation_id),
                            started_at TEXT,
                            finished_at TEXT,
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS cleanup_items (
                            cleanup_run_id TEXT NOT NULL REFERENCES cleanup_runs(cleanup_run_id) ON DELETE CASCADE,
                            item_id TEXT NOT NULL UNIQUE,
                            resource_kind TEXT NOT NULL,
                            resource_id TEXT NOT NULL,
                            observed_bytes INTEGER NOT NULL,
                            observed_token TEXT NOT NULL,
                            eligible_at TEXT NOT NULL,
                            protection_reasons TEXT NOT NULL,
                            selected INTEGER NOT NULL DEFAULT 0 CHECK(selected IN (0, 1)),
                            result TEXT,
                            released_bytes INTEGER NOT NULL DEFAULT 0,
                            reason_code TEXT,
                            reason_message TEXT,
                            PRIMARY KEY(cleanup_run_id, item_id)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS toolchain_installations (
                            installation_id TEXT PRIMARY KEY,
                            operation_id TEXT NOT NULL UNIQUE REFERENCES operations(operation_id),
                            principal_id TEXT NOT NULL,
                            plan_sha256 TEXT NOT NULL,
                            catalog_sha256 TEXT NOT NULL,
                            request_json TEXT NOT NULL,
                            state TEXT NOT NULL,
                            progress_percent INTEGER NOT NULL,
                            cancel_requested INTEGER NOT NULL DEFAULT 0,
                            reason_code TEXT,
                            reason_message TEXT,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS toolchain_installation_items (
                            installation_id TEXT NOT NULL REFERENCES toolchain_installations(installation_id) ON DELETE CASCADE,
                            ordinal INTEGER NOT NULL,
                            artifact_id TEXT NOT NULL,
                            component TEXT NOT NULL,
                            version TEXT NOT NULL,
                            state TEXT NOT NULL,
                            downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(installation_id, ordinal),
                            UNIQUE(installation_id, artifact_id)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS toolchain_inventory (
                            artifact_id TEXT PRIMARY KEY,
                            component TEXT NOT NULL,
                            version TEXT NOT NULL,
                            archive_sha256 TEXT NOT NULL,
                            content_manifest_sha256 TEXT NOT NULL,
                            installed_bytes INTEGER NOT NULL,
                            relative_path TEXT NOT NULL UNIQUE,
                            state TEXT NOT NULL,
                            installed_at TEXT NOT NULL,
                            checked_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS toolchain_license_acceptances (
                            runner_id TEXT NOT NULL,
                            principal_id TEXT NOT NULL,
                            license_id TEXT NOT NULL,
                            license_text_sha256 TEXT NOT NULL,
                            accepted_at TEXT NOT NULL,
                            PRIMARY KEY(runner_id, principal_id, license_id, license_text_sha256)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS toolchain_removal_previews (
                            preview_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL,
                            artifact_ids_json TEXT NOT NULL,
                            releasable_bytes INTEGER NOT NULL,
                            expires_at TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            idempotency_key TEXT NOT NULL,
                            request_sha256 TEXT NOT NULL,
                            UNIQUE(principal_id, idempotency_key)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS generic_build_requests (
                            job_id TEXT PRIMARY KEY REFERENCES jobs(job_id) ON DELETE CASCADE,
                            request_json TEXT NOT NULL,
                            configuration_sha256 TEXT NOT NULL,
                            comparison_id TEXT NOT NULL,
                            attempt TEXT NOT NULL,
                            retry_of_job_id TEXT REFERENCES jobs(job_id),
                            memory_bytes INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        "CREATE UNIQUE INDEX IF NOT EXISTS generic_build_attempt_identity ON generic_build_requests(comparison_id,attempt,retry_of_job_id)",
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS generic_discovery_evidence (
                            job_id TEXT PRIMARY KEY REFERENCES generic_build_requests(job_id) ON DELETE CASCADE,
                            evidence_json TEXT NOT NULL,
                            output_sha256 TEXT NOT NULL,
                            output_bytes INTEGER NOT NULL,
                            observed_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS generic_comparisons (
                            comparison_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL,
                            request_json TEXT NOT NULL,
                            request_sha256 TEXT NOT NULL,
                            response_json TEXT NOT NULL,
                            build_a_job_id TEXT NOT NULL REFERENCES jobs(job_id),
                            build_b_job_id TEXT NOT NULL REFERENCES jobs(job_id),
                            retry_of_comparison_id TEXT REFERENCES generic_comparisons(comparison_id),
                            resource_retry_count INTEGER NOT NULL DEFAULT 0,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS generic_resource_retries (
                            retry_comparison_id TEXT PRIMARY KEY,
                            original_comparison_id TEXT NOT NULL REFERENCES generic_comparisons(comparison_id),
                            build_a_job_id TEXT NOT NULL REFERENCES jobs(job_id),
                            build_b_job_id TEXT NOT NULL REFERENCES jobs(job_id),
                            evidence_code TEXT NOT NULL,
                            memory_bytes INTEGER NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS security_certificates (
                            certificate_id TEXT PRIMARY KEY,
                            generation_id TEXT NOT NULL,
                            kind TEXT NOT NULL CHECK(kind IN ('ROOT', 'LEAF')),
                            certificate_sha256 TEXT NOT NULL,
                            spki_sha256 TEXT NOT NULL,
                            serial_hex TEXT NOT NULL,
                            not_before TEXT NOT NULL,
                            not_after TEXT NOT NULL,
                            relative_path TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('ACTIVE', 'RETIRED')),
                            created_at TEXT NOT NULL,
                            UNIQUE(generation_id, kind)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS principals (
                            principal_id TEXT PRIMARY KEY,
                            kind TEXT NOT NULL CHECK(kind IN ('DEVELOPMENT', 'PAIRED')),
                            display_name TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('ACTIVE', 'REVOKED')),
                            created_at TEXT NOT NULL,
                            revoked_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        "INSERT OR IGNORE INTO principals(principal_id,kind,display_name,state,created_at) " +
                            "VALUES('local-development','DEVELOPMENT','Local development','ACTIVE',strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS pairing_invitations (
                            invitation_id TEXT PRIMARY KEY,
                            secret_sha256 TEXT NOT NULL,
                            endpoint TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('OPEN', 'CONSUMED', 'EXPIRED', 'LOCKED')),
                            invalid_attempts INTEGER NOT NULL DEFAULT 0 CHECK(invalid_attempts BETWEEN 0 AND 5),
                            expires_at TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            consumed_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS pairing_requests (
                            request_id TEXT PRIMARY KEY,
                            invitation_id TEXT NOT NULL UNIQUE REFERENCES pairing_invitations(invitation_id),
                            device_display_name TEXT NOT NULL,
                            token_id TEXT NOT NULL UNIQUE,
                            token_sha256 TEXT NOT NULL,
                            continuation_id TEXT NOT NULL UNIQUE,
                            continuation_sha256 TEXT NOT NULL,
                            confirmation_fingerprint TEXT NOT NULL,
                            principal_id TEXT REFERENCES principals(principal_id),
                            state TEXT NOT NULL CHECK(state IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED', 'FAILED')),
                            expires_at TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            decided_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS credentials (
                            token_id TEXT PRIMARY KEY,
                            token_sha256 TEXT NOT NULL,
                            principal_id TEXT NOT NULL REFERENCES principals(principal_id),
                            created_at TEXT NOT NULL,
                            last_used_at TEXT,
                            state TEXT NOT NULL CHECK(state IN ('ACTIVE', 'REVOKED')),
                            revoked_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS revocation_events (
                            event_id TEXT PRIMARY KEY,
                            principal_id TEXT NOT NULL REFERENCES principals(principal_id),
                            actor_kind TEXT NOT NULL CHECK(actor_kind IN ('SELF', 'LOCAL_CLI', 'ROOT_REPLACEMENT')),
                            reason TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS security_audit_events (
                            event_id TEXT PRIMARY KEY,
                            event_kind TEXT NOT NULL,
                            subject_id TEXT NOT NULL,
                            detail_json TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS ownership_adoption_previews (
                            preview_id TEXT PRIMARY KEY,
                            source_principal_id TEXT NOT NULL REFERENCES principals(principal_id),
                            target_principal_id TEXT NOT NULL REFERENCES principals(principal_id),
                            snapshot_sha256 TEXT NOT NULL,
                            counts_json TEXT NOT NULL,
                            state TEXT NOT NULL CHECK(state IN ('OPEN', 'EXECUTED', 'EXPIRED')),
                            expires_at TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            executed_at TEXT
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS ownership_adoption_audit (
                            audit_id TEXT PRIMARY KEY,
                            preview_id TEXT NOT NULL UNIQUE REFERENCES ownership_adoption_previews(preview_id),
                            source_principal_id TEXT NOT NULL,
                            target_principal_id TEXT NOT NULL,
                            counts_json TEXT NOT NULL,
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute("PRAGMA user_version = $SCHEMA_VERSION")
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun connection(transactionMode: SQLiteConfig.TransactionMode? = null): Connection {
        val properties = transactionMode?.let { mode ->
            SQLiteConfig().apply {
                setTransactionMode(mode)
                enforceForeignKeys(true)
                setBusyTimeout(5_000)
            }.toProperties()
        }
        return if (properties == null) {
            DriverManager.getConnection("jdbc:sqlite:$databasePath").apply {
                createStatement().use { statement ->
                    statement.execute("PRAGMA foreign_keys = ON")
                    statement.execute("PRAGMA busy_timeout = 5000")
                }
            }
        } else {
            DriverManager.getConnection("jdbc:sqlite:$databasePath", properties)
        }
    }

    private fun columnExists(connection: Connection, table: String, column: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                while (result.next()) {
                    if (result.getString("name") == column) return true
                }
                false
            }
        }

    private fun jobExists(connection: Connection, jobId: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM jobs WHERE job_id = ?").use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun getStateAndProgress(jobId: String): StateAndProgress? = connection().use { connection ->
        connection.prepareStatement(
            "SELECT state, progress_percent FROM jobs WHERE job_id = ?",
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                StateAndProgress(
                    state = JobState.valueOf(result.getString("state")),
                    progressPercent = result.getInt("progress_percent"),
                )
            }
        }
    }

    private fun deleteArtifacts(jobId: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM artifacts WHERE job_id = ?").use { statement ->
                statement.setString(1, jobId)
                statement.executeUpdate()
            }
        }
    }

    private fun clearConfirmation(jobId: String) {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE jobs SET requires_confirmation = 0 WHERE job_id = ?",
            ).use { statement ->
                statement.setString(1, jobId)
                statement.executeUpdate()
            }
        }
    }

    private fun deleteJob(jobId: String) {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM jobs WHERE job_id = ?").use { statement ->
                statement.setString(1, jobId)
                statement.executeUpdate()
            }
        }
    }

    private fun latestLogSequence(connection: Connection, jobId: String): Long =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(sequence), 0) FROM log_entries WHERE job_id = ?",
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1)
            }
        }

    private fun listArtifacts(connection: Connection, jobId: String): List<ArtifactMetadata> =
        connection.prepareStatement(
            """
            SELECT artifact_id, file_name, size_bytes, sha256, package_name, version_name, version_code
            FROM artifacts WHERE job_id = ? ORDER BY artifact_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.toArtifactMetadata())
                    }
                }
            }
        }

    private fun sourceScanSummary(connection: Connection, jobId: String, jobRow: ResultSet): SourceScanSummaryResponse? {
        val stored = loadSourceScan(connection, jobId)
        if (stored != null) {
            return SourceScanSummaryResponse(
                status = SourceScanStatus.COMPLETED,
                scannerVersion = stored.detail.scannerVersion,
                resultSha256 = stored.detail.resultSha256,
                scannedFiles = stored.detail.summary.scannedFiles,
                scannedBytes = stored.detail.summary.scannedBytes,
                findingCount = stored.detail.summary.findingCount,
                requiresReview = stored.requiresReview,
                reviewed = stored.reviewed,
            )
        }
        val state = JobState.valueOf(jobRow.getString("state"))
        if (state == JobState.SCANNING_SOURCE) {
            return SourceScanSummaryResponse(SourceScanStatus.SCANNING, SOURCE_SCANNER_VERSION)
        }
        val errorCode = jobRow.getString("error_code")
        if (state == JobState.FAILED && errorCode?.startsWith("SOURCE_SCAN_") == true) {
            return SourceScanSummaryResponse(SourceScanStatus.FAILED, SOURCE_SCANNER_VERSION)
        }
        return null
    }

    private fun loadSourceScan(connection: Connection, jobId: String): StoredSourceScan? {
        val header = connection.prepareStatement(
            """
            SELECT schema_version, resolved_commit_sha, scanner_version, result_sha256, canonical_json,
                   scanned_files, scanned_bytes, skipped_binary_files, skipped_symlinks, finding_count,
                   requires_review, reviewed
            FROM source_scan_results WHERE job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                SourceScanHeader(
                    schemaVersion = rows.getInt("schema_version"),
                    resolvedCommitSha = rows.getString("resolved_commit_sha"),
                    scannerVersion = rows.getString("scanner_version"),
                    resultSha256 = rows.getString("result_sha256"),
                    canonicalBytes = rows.getBytes("canonical_json"),
                    summary = SourceScanStatistics(
                        scannedFiles = rows.getInt("scanned_files"),
                        scannedBytes = rows.getLong("scanned_bytes"),
                        skippedBinaryFiles = rows.getInt("skipped_binary_files"),
                        skippedSymlinks = rows.getInt("skipped_symlinks"),
                        findingCount = rows.getInt("finding_count"),
                    ),
                    requiresReview = rows.getInt("requires_review") != 0,
                    reviewed = rows.getInt("reviewed") != 0,
                )
            }
        }
        val counts = connection.prepareStatement(
            "SELECT detector_id, finding_count FROM source_scan_detector_counts WHERE job_id = ? ORDER BY detector_id",
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(SourceScanDetectorCount(SourceScanDetectorId.valueOf(rows.getString(1)), rows.getInt(2)))
                    }
                }
            }
        }
        val findings = connection.prepareStatement(
            """
            SELECT detector_id, display_path, line, column_number
            FROM source_scan_findings WHERE job_id = ? ORDER BY ordinal
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val line = rows.getInt("line").takeUnless { rows.wasNull() }
                        val column = rows.getInt("column_number").takeUnless { rows.wasNull() }
                        add(
                            SourceScanFinding(
                                SourceScanDetectorId.valueOf(rows.getString("detector_id")),
                                rows.getString("display_path"),
                                line,
                                column,
                            ),
                        )
                    }
                }
            }
        }
        val reconstructedCanonicalBytes = canonicalSourceScanBytes(
            schemaVersion = header.schemaVersion,
            resolvedCommitSha = header.resolvedCommitSha,
            scannerVersion = header.scannerVersion,
            summary = header.summary,
            detectorCounts = counts,
            findings = findings,
        )
        if (
            !header.canonicalBytes.contentEquals(reconstructedCanonicalBytes) ||
            sourceScanSha256(header.canonicalBytes) != header.resultSha256
        ) {
            throw TrustedBuildFailure(
                "SOURCE_SCAN_INVALID",
                "Stored source scan evidence failed integrity verification.",
            )
        }
        return StoredSourceScan(
            detail = SourceScanDetailResponse(
                schemaVersion = header.schemaVersion,
                jobId = jobId,
                resolvedCommitSha = header.resolvedCommitSha,
                scannerVersion = header.scannerVersion,
                resultSha256 = header.resultSha256,
                summary = header.summary,
                detectorCounts = counts,
                findings = findings,
            ),
            canonicalBytes = header.canonicalBytes,
            requiresReview = header.requiresReview,
            reviewed = header.reviewed,
        )
    }

    private fun ResultSet.toStoredJob(): StoredJob = StoredJob(
        jobId = getString("job_id"),
        principalId = getString("principal_id"),
        executionMode = ExecutionMode.valueOf(getString("execution_mode")),
        repositoryUrl = getString("repository_url"),
        revision = RequestedRevision(
            type = RevisionType.valueOf(getString("revision_type")),
            value = getString("revision_value"),
        ),
        simulationOutcome = getString("simulation_outcome")?.let(SimulationOutcome::valueOf),
        resolvedCommitSha = getString("resolved_commit_sha"),
        state = JobState.valueOf(getString("state")),
        sandbox = toSandboxSnapshot(),
    )

    private fun ResultSet.toSandboxSnapshot(): SandboxSnapshot? {
        try {
            val columns = SANDBOX_COLUMNS.map { it.substringBefore(' ') }
            if (ExecutionMode.valueOf(getString("execution_mode")) == ExecutionMode.SIMULATED) {
                require(columns.all { getString(it) == null })
                return null
            }
            val sandbox = JobSandbox(
                mode = BuildSandboxMode.valueOf(getString("sandbox_mode")),
                origin = SandboxOrigin.valueOf(getString("sandbox_origin")),
                profileId = getString("sandbox_profile_id"),
                cleanupStatus = getString("sandbox_cleanup_status")?.let(SandboxCleanupStatus::valueOf),
            )
            sandbox.validateState(JobState.valueOf(getString("state")))
            return SandboxSnapshot(
                sandbox, getString("sandbox_snapshot"), getString("sandbox_snapshot_sha256"),
                SandboxManifestFormat.valueOf(getString("manifest_format")),
            ).also { it.validateAnyProfile() }
        } catch (_: Exception) {
            throw invalidSandboxSnapshot()
        }
    }

    private fun ResultSet.toArtifactMetadata(): ArtifactMetadata = ArtifactMetadata(
        artifactId = getString("artifact_id"),
        fileName = getString("file_name"),
        sizeBytes = getLong("size_bytes"),
        sha256 = getString("sha256"),
        packageName = getString("package_name"),
        versionName = getString("version_name"),
        versionCode = getLong("version_code"),
    )

    private fun ResultSet.toJobResponse(
        latestLogSequence: Long,
        artifacts: List<ArtifactMetadata>,
        sourceScan: SourceScanSummaryResponse?,
        genericBuild: GenericBuildSnapshot?,
        discovery: GenericDiscoveryEvidence?,
    ): JobResponse = JobResponse(
        jobId = getString("job_id"),
        executionMode = ExecutionMode.valueOf(getString("execution_mode")),
        repositoryUrl = getString("repository_url"),
        requestedRevision = RequestedRevision(
            type = RevisionType.valueOf(getString("revision_type")),
            value = getString("revision_value"),
        ),
        resolvedCommitSha = getString("resolved_commit_sha"),
        state = JobState.valueOf(getString("state")),
        progressPercent = getInt("progress_percent"),
        requiresConfirmation = getInt("requires_confirmation") != 0,
        effectiveBuild = getString("effective_build_root")?.let { buildRoot ->
            EffectiveBuild(
                recipeId = getString("effective_recipe_id"),
                variantName = getString("effective_variant_name"),
                buildRoot = buildRoot,
                javaMajor = getInt("effective_java_major").takeUnless { wasNull() },
                tasks = getString("effective_build_tasks")
                    ?.split('\n')
                    ?.filter(String::isNotBlank)
                    .orEmpty(),
                dependencyPinning = DependencyPinning.valueOf(getString("effective_dependency_pinning")),
                determinism = DeterminismOptions(
                    sourceDateEpoch = getLong("effective_source_date_epoch").takeUnless { wasNull() },
                    noBuildCache = getInt("effective_no_build_cache") != 0,
                    fixedLocale = getString("effective_fixed_locale")?.let(FixedLocale::valueOf),
                ),
            )
        },
        sourceScan = sourceScan,
        latestLogSequence = latestLogSequence,
        artifacts = artifacts,
        error = getString("error_code")?.let { code ->
            JobError(code, getString("error_message"))
        },
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
        sandbox = toSandboxSnapshot()?.jobSandbox,
        genericBuild = genericBuild,
        discovery = discovery,
    )

    private fun genericBuild(connection: Connection, jobId: String): GenericBuildSnapshot? =
        connection.prepareStatement("SELECT request_json FROM generic_build_requests WHERE job_id=?").use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { row ->
                if (row.next()) GENERIC_JSON.decodeFromString<GenericBuildSnapshot>(row.getString(1)) else null
            }
        }

    private fun genericDiscovery(connection: Connection, jobId: String): GenericDiscoveryEvidence? =
        connection.prepareStatement("SELECT evidence_json FROM generic_discovery_evidence WHERE job_id=?").use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { row ->
                if (row.next()) GENERIC_JSON.decodeFromString<GenericDiscoveryEvidence>(row.getString(1)) else null
            }
        }

    private fun prepareLogDirectory() {
        val root = stateDirectory.toAbsolutePath().normalize()
        val directory = logDirectory.toAbsolutePath().normalize()
        check(directory.parent == root) { "JOB_LOG_DIRECTORY_OUTSIDE_STATE" }
        val attributes = if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            emptyArray()
        }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory, *attributes)
        check(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            "JOB_LOG_DIRECTORY_INVALID"
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            check(Files.getOwner(directory, NOFOLLOW_LINKS) == Files.getOwner(root, NOFOLLOW_LINKS)) {
                "JOB_LOG_OWNER_INVALID"
            }
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            check(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")) {
                "JOB_LOG_PERMISSIONS_INVALID"
            }
        }
    }

    private fun openPrivateJobLog(path: Path, options: Set<OpenOption>): FileChannel {
        val root = stateDirectory.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        check(normalized.parent == logDirectory.toAbsolutePath().normalize()) { "JOB_LOG_PATH_OUTSIDE_STATE" }
        prepareLogDirectory()
        if (Files.exists(normalized, NOFOLLOW_LINKS)) securePrivateJobLog(normalized, root)
        val attributes = if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            emptyArray()
        }
        return FileChannel.open(normalized, options + NOFOLLOW_LINKS, *attributes).also { channel ->
            try {
                securePrivateJobLog(normalized, root)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }
    }

    private fun securePrivateJobLog(path: Path, root: Path) {
        check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "JOB_LOG_FILE_INVALID" }
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) return
        check(Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(root, NOFOLLOW_LINKS)) { "JOB_LOG_OWNER_INVALID" }
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        check(permissions.none {
            it == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE ||
                it == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
        }) { "JOB_LOG_PERMISSIONS_INVALID" }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        check(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------")) {
            "JOB_LOG_PERMISSIONS_INVALID"
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "JOB_LOG_WRITE_INCOMPLETE" }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun logPath(jobId: String): Path = logDirectory.resolve("$jobId.log")

    private data class IndexedLogEntry(
        val sequence: Long,
        val timestamp: String,
        val level: LogLevel,
        val offset: Long,
        val byteLength: Int,
    )

    private data class StateAndProgress(
        val state: JobState,
        val progressPercent: Int,
    )

    private data class ReviewRow(
        val state: JobState,
        val resultSha256: String?,
        val requiresReview: Boolean,
        val reviewed: Boolean,
    )

    private data class SourceScanHeader(
        val schemaVersion: Int,
        val resolvedCommitSha: String,
        val scannerVersion: String,
        val resultSha256: String,
        val canonicalBytes: ByteArray,
        val summary: SourceScanStatistics,
        val requiresReview: Boolean,
        val reviewed: Boolean,
    )

    companion object {
        const val SCHEMA_VERSION = 12
        const val LOCAL_DEVELOPMENT_PRINCIPAL = "local-development"
        private const val LOCAL_PRINCIPAL = LOCAL_DEVELOPMENT_PRINCIPAL
        val GENERIC_JSON = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }
        val SANDBOX_COLUMNS = listOf(
            "sandbox_mode TEXT", "sandbox_origin TEXT", "sandbox_profile_id TEXT", "sandbox_snapshot TEXT",
            "sandbox_snapshot_sha256 TEXT", "sandbox_cleanup_status TEXT", "manifest_format TEXT",
        )
        const val SOURCE_SCANNER_VERSION = "reprodroid-static-v1"
        val AUDIT_COLUMNS = listOf(
            "gradle_version TEXT",
            "distribution_url TEXT",
            "distribution_sha256 TEXT",
            "distribution_checksum_source TEXT",
            "wrapper_jar_gradle_version TEXT",
            "wrapper_jar_sha256 TEXT",
            "manifest_path TEXT",
            "manifest_sha256 TEXT",
        )
        val BUILD_PROFILE_COLUMNS = listOf(
            "effective_recipe_id TEXT",
            "effective_variant_name TEXT",
            "effective_java_major INTEGER",
        )
        val PINNING_COLUMNS = listOf(
            "effective_dependency_pinning TEXT NOT NULL DEFAULT 'NONE'",
            "dependency_lock_pre_sha256 TEXT",
            "dependency_lock_post_sha256 TEXT",
        )
        val DETERMINISM_COLUMNS = listOf(
            "effective_source_date_epoch INTEGER",
            "effective_no_build_cache INTEGER NOT NULL DEFAULT 0",
            "effective_fixed_locale TEXT",
        )
    }
}
