package com.sanka1610.reprodroid.runner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.http.HttpStatusCode
import org.erdtman.jcs.JsonCanonicalizer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.io.path.exists

internal data class OperationReceipt(
    val existing: Boolean,
    val response: V2OperationResponse,
)

internal class StorageRetentionStore(
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
    private val json = Json { explicitNulls = true; encodeDefaults = true }

    init {
        require(Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS))
        Class.forName("org.sqlite.JDBC")
        initializeDefaults()
        reconcileInterruptedOperations()
    }

    @Synchronized
    fun runnerId(): String = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT runner_id FROM runner_identity WHERE singleton = 1").use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }
    }

    @Synchronized
    fun operation(operationId: String, principalId: String): V2OperationResponse = connection().use { connection ->
        connection.prepareStatement(
            "SELECT * FROM operations WHERE operation_id = ? AND principal_id = ?",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.setString(2, principalId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw resourceNotFound()
                rows.toOperationResponse()
            }
        }
    }

    @Synchronized
    fun storageSummary(): StorageSummaryResponse {
        val measuredAt = now()
        val jobMeasurement = measureRoots(JOB_ROOTS)
        val toolchainMeasurement = measureRoots(TOOLCHAIN_ROOTS)
        val totalMeasurement = measureRoots(listOf(stateDirectory))
        val unclassified = if (
            totalMeasurement.state == StorageMeasurementState.COMPLETE &&
            jobMeasurement.state == StorageMeasurementState.COMPLETE &&
            toolchainMeasurement.state == StorageMeasurementState.COMPLETE
        ) {
            (totalMeasurement.bytes - jobMeasurement.bytes - toolchainMeasurement.bytes).coerceAtLeast(0)
        } else {
            0
        }
        val usable = runCatching { Files.getFileStore(stateDirectory).usableSpace }.getOrNull()
        val settings = storageSettings()
        val reservations = activeReservationBytes()
        val areas = listOf(StorageArea.RUNNER_JOB, StorageArea.RUNNER_TOOLCHAIN).map { area ->
            val measurement = if (area == StorageArea.RUNNER_JOB) jobMeasurement else toolchainMeasurement
            val budget = requireNotNull(settings[area])
            val reserved = reservations[area] ?: 0L
            val state = when {
                usable == null || measurement.state == StorageMeasurementState.FAILED -> StorageAreaState.STORAGE_UNAVAILABLE
                measurement.bytes > budget.first -> StorageAreaState.OVER_BUDGET
                reachesWarning(measurement.bytes, reserved, budget.first, budget.second) -> StorageAreaState.WARNING
                else -> StorageAreaState.OK
            }
            StorageAreaSummary(
                area = area,
                budgetBytes = budget.first.toString(),
                usedBytes = measurement.bytes.toString(),
                reservedBytes = reserved.toString(),
                unclassifiedBytes = if (area == StorageArea.RUNNER_JOB) unclassified.toString() else "0",
                usableBytes = (usable ?: 0L).toString(),
                warningPercent = budget.second,
                state = state,
                measurementState = measurement.state,
                measuredAt = measuredAt,
            )
        }
        return StorageSummaryResponse(runnerId = runnerId(), areas = areas)
    }

    @Synchronized
    fun createHold(
        principalId: String,
        idempotencyKey: String,
        command: HoldCreateCommand,
    ): OperationReceipt {
        existingOperation(principalId, OP_HOLD_CREATE, idempotencyKey, command.normalizedRequest)?.let { return it }
        requireResourceExists(command.resourceKind, command.resourceId)
        val reserved = reserveOperation(principalId, OP_HOLD_CREATE, idempotencyKey, command.normalizedRequest)
        if (reserved.existing) return reserved
        transitionOperation(reserved.response.operationId, V2OperationState.APPLYING)
        return try {
            val holdId = transaction { connection ->
                activeHoldId(connection, principalId, command)?.let { return@transaction it }
                val id = UUID.randomUUID().toString()
                connection.prepareStatement(
                    """
                    INSERT INTO retention_holds (
                        hold_id, principal_id, resource_kind, resource_id, reason,
                        client_reference_type, client_reference_id, state,
                        created_operation_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, id)
                    statement.setString(2, principalId)
                    statement.setString(3, command.resourceKind.name)
                    statement.setString(4, command.resourceId)
                    statement.setString(5, command.reason)
                    statement.setString(6, command.clientReferenceType)
                    statement.setString(7, command.clientReferenceId)
                    statement.setString(8, reserved.response.operationId)
                    statement.setString(9, now())
                    check(statement.executeUpdate() == 1)
                }
                id
            }
            completeOperation(reserved.response.operationId, "RETENTION_HOLD", holdId, null)
            OperationReceipt(false, operation(reserved.response.operationId, principalId))
        } catch (failure: Throwable) {
            rejectOperation(reserved.response.operationId, "HOLD_CREATE_FAILED", "The retention hold could not be created.")
            throw failure
        }
    }

    @Synchronized
    fun releaseHold(
        principalId: String,
        idempotencyKey: String,
        holdId: String,
        normalizedRequest: JsonObject,
    ): OperationReceipt {
        val request = appendTarget(normalizedRequest, "holdId", holdId)
        existingOperation(principalId, OP_HOLD_RELEASE, idempotencyKey, request)?.let { return it }
        requireOwnedActiveHold(principalId, holdId)
        val reserved = reserveOperation(principalId, OP_HOLD_RELEASE, idempotencyKey, request)
        if (reserved.existing) return reserved
        transitionOperation(reserved.response.operationId, V2OperationState.APPLYING)
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE retention_holds SET state = 'RELEASED', released_operation_id = ?, released_at = ?
                WHERE hold_id = ? AND principal_id = ? AND state = 'ACTIVE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, reserved.response.operationId)
                statement.setString(2, now())
                statement.setString(3, holdId)
                statement.setString(4, principalId)
                if (statement.executeUpdate() != 1) throw resourceNotFound()
            }
        }
        completeOperation(reserved.response.operationId, "RETENTION_HOLD", holdId, null)
        return OperationReceipt(false, operation(reserved.response.operationId, principalId))
    }

    @Synchronized
    fun createReservation(
        principalId: String,
        idempotencyKey: String,
        command: ReservationCreateCommand,
    ): OperationReceipt {
        existingOperation(principalId, OP_RESERVATION_CREATE, idempotencyKey, command.normalizedRequest)?.let { return it }
        ensureNoReconciliationRequired()
        requireResourceExists(command.resourceKind, command.resourceId)
        if (command.area != StorageArea.RUNNER_JOB) {
            throw ApiException.conflict("CAPABILITY_UNAVAILABLE", "Toolchain storage reservations are not available in this contract.")
        }
        ensureNoActiveReservation(principalId, command)
        ensureCapacity(command.area, command.requestedBytes)
        val reserved = reserveOperation(principalId, OP_RESERVATION_CREATE, idempotencyKey, command.normalizedRequest)
        if (reserved.existing) return reserved
        transitionOperation(reserved.response.operationId, V2OperationState.APPLYING)
        val reservationId = transaction { connection ->
            val id = UUID.randomUUID().toString()
            connection.prepareStatement(
                """
                INSERT INTO storage_reservations (
                    reservation_id, principal_id, area, purpose, resource_kind, resource_id,
                    requested_bytes, state, created_operation_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                val current = now()
                statement.setString(1, id)
                statement.setString(2, principalId)
                statement.setString(3, command.area.name)
                statement.setString(4, command.purpose)
                statement.setString(5, command.resourceKind.name)
                statement.setString(6, command.resourceId)
                statement.setLong(7, command.requestedBytes)
                statement.setString(8, reserved.response.operationId)
                statement.setString(9, current)
                statement.setString(10, current)
                check(statement.executeUpdate() == 1)
            }
            id
        }
        completeOperation(reserved.response.operationId, "STORAGE_RESERVATION", reservationId, null)
        return OperationReceipt(false, operation(reserved.response.operationId, principalId))
    }

    @Synchronized
    fun releaseReservation(
        principalId: String,
        idempotencyKey: String,
        reservationId: String,
        normalizedRequest: JsonObject,
    ): OperationReceipt {
        val request = appendTarget(normalizedRequest, "reservationId", reservationId)
        existingOperation(principalId, OP_RESERVATION_RELEASE, idempotencyKey, request)?.let { return it }
        requireOwnedActiveReservation(principalId, reservationId)
        val reserved = reserveOperation(principalId, OP_RESERVATION_RELEASE, idempotencyKey, request)
        if (reserved.existing) return reserved
        transitionOperation(reserved.response.operationId, V2OperationState.APPLYING)
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE storage_reservations
                SET state = 'RELEASED', released_operation_id = ?, updated_at = ?
                WHERE reservation_id = ? AND principal_id = ? AND state = 'ACTIVE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, reserved.response.operationId)
                statement.setString(2, now())
                statement.setString(3, reservationId)
                statement.setString(4, principalId)
                if (statement.executeUpdate() != 1) throw resourceNotFound()
            }
        }
        completeOperation(reserved.response.operationId, "STORAGE_RESERVATION", reservationId, null)
        return OperationReceipt(false, operation(reserved.response.operationId, principalId))
    }

    @Synchronized
    fun createCleanupPreview(
        principalId: String,
        idempotencyKey: String,
        command: CleanupPreviewCommand,
    ): OperationReceipt {
        if (command.area != StorageArea.RUNNER_JOB) {
            throw ApiException.conflict("CAPABILITY_UNAVAILABLE", "Toolchain cleanup is not available in this contract.")
        }
        val reserved = reserveOperation(principalId, OP_CLEANUP_PREVIEW, idempotencyKey, command.normalizedRequest)
        if (reserved.existing) return reserved
        transitionOperation(reserved.response.operationId, V2OperationState.APPLYING)
        return try {
            val candidates = inventory(command)
            val truncated = candidates.size > MAX_PREVIEW_ITEMS
            val selected = candidates.take(MAX_PREVIEW_ITEMS)
            val previewId = UUID.randomUUID().toString()
            val cleanupRunId = UUID.randomUUID().toString()
            val current = Instant.now(clock)
            val expiresAt = current.plus(Duration.ofMinutes(30)).toString()
            transaction { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO cleanup_runs (
                        cleanup_run_id, preview_id, principal_id, area, filter_sha256, state,
                        truncated, expires_at, created_operation_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'PREVIEWED', ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, cleanupRunId)
                    statement.setString(2, previewId)
                    statement.setString(3, principalId)
                    statement.setString(4, command.area.name)
                    statement.setString(5, requestSha256(OP_CLEANUP_PREVIEW, command.normalizedRequest))
                    statement.setInt(6, if (truncated) 1 else 0)
                    statement.setString(7, expiresAt)
                    statement.setString(8, reserved.response.operationId)
                    statement.setString(9, current.toString())
                    check(statement.executeUpdate() == 1)
                }
                selected.forEach { candidate ->
                    connection.prepareStatement(
                        """
                        INSERT INTO cleanup_items (
                            cleanup_run_id, item_id, resource_kind, resource_id, observed_bytes,
                            observed_token, eligible_at, protection_reasons
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, cleanupRunId)
                        statement.setString(2, candidate.itemId)
                        statement.setString(3, candidate.kind.name)
                        statement.setString(4, candidate.resourceId)
                        statement.setLong(5, candidate.observation.bytes)
                        statement.setString(6, candidate.observation.token)
                        statement.setString(7, candidate.eligibleAt)
                        statement.setString(8, candidate.protections.sorted().joinToString(","))
                        check(statement.executeUpdate() == 1)
                    }
                }
            }
            val response = cleanupPreview(previewId, principalId)
            completeOperation(
                reserved.response.operationId,
                "CLEANUP_PREVIEW",
                previewId,
                json.encodeToString(response),
            )
            OperationReceipt(false, operation(reserved.response.operationId, principalId))
        } catch (failure: Throwable) {
            rejectOperation(reserved.response.operationId, "CLEANUP_PREVIEW_FAILED", "The cleanup preview could not be created.")
            throw failure
        }
    }

    @Synchronized
    fun cleanupPreview(previewId: String, principalId: String): CleanupPreviewResponse = connection().use { connection ->
        connection.prepareStatement(
            "SELECT * FROM cleanup_runs WHERE preview_id = ? AND principal_id = ?",
        ).use { statement ->
            statement.setString(1, previewId)
            statement.setString(2, principalId)
            statement.executeQuery().use { run ->
                if (!run.next()) throw resourceNotFound()
                val items = cleanupItems(connection, run.getString("cleanup_run_id")).map { row ->
                    CleanupPreviewItemResponse(
                        itemId = row.itemId,
                        resourceKind = row.kind.name,
                        resourceId = row.resourceId,
                        observedBytes = row.observedBytes.toString(),
                        observedToken = row.observedToken,
                        eligibleAt = row.eligibleAt,
                        protectionReasons = row.protections,
                    )
                }
                CleanupPreviewResponse(
                    previewId = previewId,
                    runnerId = runnerId(),
                    state = run.getString("state"),
                    expiresAt = run.getString("expires_at"),
                    truncated = run.getInt("truncated") != 0,
                    items = items,
                )
            }
        }
    }

    @Synchronized
    fun executeCleanup(
        principalId: String,
        idempotencyKey: String,
        previewId: String,
        command: CleanupExecuteCommand,
    ): OperationReceipt {
        val request = appendTarget(command.normalizedRequest, "previewId", previewId)
        existingOperation(principalId, OP_CLEANUP_EXECUTE, idempotencyKey, request)?.let { return it }
        ensureNoReconciliationRequired()
        val preview = requireExecutablePreview(previewId, principalId, command.itemIds)
        val reserved = reserveOperation(principalId, OP_CLEANUP_EXECUTE, idempotencyKey, request)
        if (reserved.existing) return reserved
        transitionOperation(reserved.response.operationId, V2OperationState.APPLYING)
        val startedAt = now()
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE cleanup_runs SET state = 'APPLYING', execute_operation_id = ?, started_at = ?
                WHERE cleanup_run_id = ? AND state = 'PREVIEWED'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, reserved.response.operationId)
                statement.setString(2, startedAt)
                statement.setString(3, preview.cleanupRunId)
                if (statement.executeUpdate() != 1) {
                    throw ApiException.conflict("CLEANUP_ALREADY_EXECUTED", "The cleanup preview was already executed.")
                }
            }
            connection.prepareStatement(
                "UPDATE cleanup_items SET selected = 1 WHERE cleanup_run_id = ? AND item_id = ?",
            ).use { statement ->
                command.itemIds.forEach { itemId ->
                    statement.setString(1, preview.cleanupRunId)
                    statement.setString(2, itemId)
                    check(statement.executeUpdate() == 1)
                }
            }
        }

        var reconciliationRequired = false
        command.itemIds.forEach { itemId ->
            val row = preview.items.single { it.itemId == itemId }
            val result = evaluateAndDelete(row)
            try {
                persistCleanupItem(preview.cleanupRunId, row, result)
            } catch (failure: Throwable) {
                if (result.result in setOf(CleanupItemResult.DELETED, CleanupItemResult.ALREADY_MISSING)) {
                    reconciliationRequired = true
                } else {
                    throw failure
                }
            }
        }
        if (reconciliationRequired) {
            markCleanupReconciliation(preview.cleanupRunId, reserved.response.operationId)
            return OperationReceipt(false, operation(reserved.response.operationId, principalId))
        }
        val response = finalizeCleanup(preview.cleanupRunId)
        completeOperation(
            reserved.response.operationId,
            "CLEANUP_RUN",
            response.cleanupRunId,
            json.encodeToString(response),
        )
        return OperationReceipt(false, operation(reserved.response.operationId, principalId))
    }

    @Synchronized
    fun cleanupRun(cleanupRunId: String, principalId: String): CleanupRunResponse = connection().use { connection ->
        cleanupRun(connection, cleanupRunId, principalId)
    }

    private fun inventory(command: CleanupPreviewCommand): List<ResourceCandidate> {
        val cutoff = Instant.parse(command.eligibleBefore)
        val candidates = mutableListOf<ResourceCandidate>()
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT job_id, state, updated_at, sandbox_cleanup_status FROM jobs ORDER BY job_id",
                ).use { rows ->
                    while (rows.next()) {
                        val jobId = rows.getString("job_id")
                        val updatedAt = Instant.parse(rows.getString("updated_at"))
                        val state = runCatching { JobState.valueOf(rows.getString("state")) }.getOrNull()
                        val cleanupStatus = rows.getString("sandbox_cleanup_status")
                        val jobProtections = mutableSetOf<String>()
                        if (state == null || !state.isTerminal) jobProtections += "ACTIVE_JOB"
                        if (state == JobState.AWAITING_SCAN_REVIEW) jobProtections += "AWAITING_REVIEW"
                        if (cleanupStatus == SandboxCleanupStatus.PENDING.name) jobProtections += "SANDBOX_CLEANUP_PENDING"
                        if (hasActiveProtection(connection, RetentionResourceKind.JOB, jobId)) {
                            jobProtections += "RETENTION_HOLD"
                        }
                        if (hasActiveReservation(connection, RetentionResourceKind.JOB, jobId)) {
                            jobProtections += "ACTIVE_RESERVATION"
                        }
                        fun addJobResource(kind: CleanupResourceKind, path: Path, ageDays: Long) {
                            if (kind !in command.resourceKinds) return
                            if (command.resourceIds.isNotEmpty() && jobId !in command.resourceIds) return
                            val eligible = updatedAt.plus(ageDays, ChronoUnit.DAYS)
                            if (eligible > cutoff) return
                            candidates += candidate(kind, jobId, path, eligible, jobProtections)
                        }
                        addJobResource(
                            CleanupResourceKind.JOB_WORKSPACE,
                            stateDirectory.resolve("workspaces").resolve(jobId),
                            7,
                        )
                        addJobResource(
                            CleanupResourceKind.JOB_MANIFEST,
                            stateDirectory.resolve("manifests").resolve(jobId),
                            90,
                        )
                        addJobResource(
                            CleanupResourceKind.JOB_LOG,
                            stateDirectory.resolve("logs").resolve("$jobId.log"),
                            90,
                        )
                        addJobResource(
                            CleanupResourceKind.SANDBOX_IMPORT,
                            stateDirectory.resolve("sandbox-imports").resolve(jobId),
                            7,
                        )
                    }
                }
            }
            if (CleanupResourceKind.JOB_ARTIFACT in command.resourceKinds) {
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        SELECT a.artifact_id, a.job_id, a.content_path, j.updated_at, j.state,
                               j.sandbox_cleanup_status
                        FROM artifacts a JOIN jobs j ON j.job_id = a.job_id ORDER BY a.artifact_id
                        """.trimIndent(),
                    ).use { rows ->
                        while (rows.next()) {
                            val artifactId = rows.getString("artifact_id")
                            if (command.resourceIds.isNotEmpty() && artifactId !in command.resourceIds) continue
                            val updatedAt = Instant.parse(rows.getString("updated_at"))
                            val eligible = updatedAt.plus(30, ChronoUnit.DAYS)
                            if (eligible > cutoff) continue
                            val protections = mutableSetOf<String>()
                            val state = runCatching { JobState.valueOf(rows.getString("state")) }.getOrNull()
                            if (state == null || !state.isTerminal) protections += "ACTIVE_JOB"
                            if (rows.getString("sandbox_cleanup_status") == SandboxCleanupStatus.PENDING.name) {
                                protections += "SANDBOX_CLEANUP_PENDING"
                            }
                            if (
                                hasActiveProtection(connection, RetentionResourceKind.ARTIFACT, artifactId) ||
                                hasActiveProtection(connection, RetentionResourceKind.JOB, rows.getString("job_id"))
                            ) protections += "RETENTION_HOLD"
                            if (hasActiveReservation(connection, RetentionResourceKind.ARTIFACT, artifactId)) {
                                protections += "ACTIVE_RESERVATION"
                            }
                            val relative = rows.getString("content_path")
                            val path = relative?.let { confinedStatePath(it) }
                                ?: stateDirectory.resolve("artifacts")
                                    .resolve(rows.getString("job_id"))
                                    .resolve("$artifactId.apk")
                            candidates += candidate(
                                CleanupResourceKind.JOB_ARTIFACT,
                                artifactId,
                                path,
                                eligible,
                                protections,
                            )
                        }
                    }
                }
            }
        }
        return candidates.sortedWith(compareBy(ResourceCandidate::kind, ResourceCandidate::resourceId))
    }

    private fun candidate(
        kind: CleanupResourceKind,
        resourceId: String,
        path: Path,
        eligibleAt: Instant,
        protections: Set<String>,
    ): ResourceCandidate {
        val observation = observe(path, kind, resourceId)
        val effectiveProtections = protections.toMutableSet()
        if (observation.unsafe) effectiveProtections += "RESOURCE_CHANGED"
        return ResourceCandidate(
            itemId = UUID.randomUUID().toString(),
            kind = kind,
            resourceId = resourceId,
            path = path,
            eligibleAt = eligibleAt.toString(),
            observation = observation,
            protections = effectiveProtections,
        )
    }

    private fun observe(path: Path, kind: CleanupResourceKind, resourceId: String): ResourceObservation {
        val root = stateDirectory.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) return ResourceObservation(0, token(kind, resourceId, "UNSAFE"), true, false)
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return ResourceObservation(0, token(kind, resourceId, "MISSING"), false, false)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(kind.name.toByteArray())
        digest.update(resourceId.toByteArray())
        var bytes = 0L
        var entries = 0
        return try {
            Files.walkFileTree(normalized, setOf(), MAX_WALK_DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    register(directory, attributes)
                    if (attributes.isSymbolicLink) throw UnsafeStorageEntry()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    register(file, attributes)
                    if (attributes.isSymbolicLink || !attributes.isRegularFile) throw UnsafeStorageEntry()
                    bytes = Math.addExact(bytes, attributes.size())
                    return FileVisitResult.CONTINUE
                }

                private fun register(entry: Path, attributes: BasicFileAttributes) {
                    entries++
                    if (entries > MAX_WALK_ENTRIES) throw WalkIncomplete()
                    val relative = normalized.relativize(entry).toString().replace('\\', '/')
                    digest.update(relative.toByteArray())
                    digest.update(attributes.size().toString().toByteArray())
                    digest.update(attributes.lastModifiedTime().toMillis().toString().toByteArray())
                    digest.update((attributes.fileKey()?.toString() ?: "none").toByteArray())
                }
            })
            ResourceObservation(bytes, digest.digest().hex(), false, true)
        } catch (_: Throwable) {
            ResourceObservation(bytes, token(kind, resourceId, "UNSAFE"), true, true)
        }
    }

    private fun evaluateAndDelete(row: CleanupItemRow): ItemExecutionResult {
        val current = resolveCurrentCandidate(row)
            ?: return ItemExecutionResult(CleanupItemResult.FAILED, 0, "RESOURCE_NOT_FOUND", "The resource header no longer exists.")
        if (current.protections.isNotEmpty()) {
            val code = current.protections.sorted().first()
            return ItemExecutionResult(
                CleanupItemResult.SKIPPED_PROTECTED,
                0,
                code,
                publicProtectionMessage(code),
            )
        }
        if (!current.observation.exists) {
            return ItemExecutionResult(CleanupItemResult.ALREADY_MISSING, 0, null, null)
        }
        if (current.observation.token != row.observedToken) {
            return ItemExecutionResult(
                CleanupItemResult.SKIPPED_PROTECTED,
                0,
                "RESOURCE_CHANGED",
                "The resource changed after the cleanup preview.",
            )
        }
        return try {
            safeDelete(current.path)
            ItemExecutionResult(CleanupItemResult.DELETED, current.observation.bytes, null, null)
        } catch (_: Throwable) {
            ItemExecutionResult(CleanupItemResult.FAILED, 0, "DELETE_FAILED", "The resource could not be deleted safely.")
        }
    }

    private fun resolveCurrentCandidate(row: CleanupItemRow): ResourceCandidate? {
        val command = CleanupPreviewCommand(
            area = StorageArea.RUNNER_JOB,
            resourceKinds = setOf(row.kind),
            eligibleBefore = Instant.now(clock).toString(),
            resourceIds = setOf(row.resourceId),
            normalizedRequest = JsonObject(emptyMap()),
        )
        return inventory(command).singleOrNull()?.copy(itemId = row.itemId, eligibleAt = row.eligibleAt)
    }

    private fun safeDelete(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val root = stateDirectory.toAbsolutePath().normalize()
        check(normalized != root && normalized.startsWith(root))
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return
        val rootAttributes = Files.readAttributes(normalized, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(!rootAttributes.isSymbolicLink)
        if (rootAttributes.isRegularFile) {
            Files.delete(normalized)
            return
        }
        check(rootAttributes.isDirectory)
        Files.walkFileTree(normalized, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                check(attributes.isRegularFile && !attributes.isSymbolicLink)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun persistCleanupItem(cleanupRunId: String, row: CleanupItemRow, result: ItemExecutionResult) {
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE cleanup_items SET result = ?, released_bytes = ?, reason_code = ?, reason_message = ?
                WHERE cleanup_run_id = ? AND item_id = ? AND result IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, result.result.name)
                statement.setLong(2, result.releasedBytes)
                statement.setString(3, result.reasonCode)
                statement.setString(4, result.reasonMessage)
                statement.setString(5, cleanupRunId)
                statement.setString(6, row.itemId)
                check(statement.executeUpdate() == 1)
            }
            if (result.result in setOf(CleanupItemResult.DELETED, CleanupItemResult.ALREADY_MISSING)) {
                val availabilityState = if (result.result == CleanupItemResult.DELETED) "DELETED" else "MISSING"
                connection.prepareStatement(
                    """
                    INSERT INTO resource_availability (
                        resource_kind, resource_id, state, observed_bytes, checked_at,
                        deletion_run_id, deletion_reason
                    ) VALUES (?, ?, ?, 0, ?, ?, 'MANUAL_CLEANUP')
                    ON CONFLICT(resource_kind, resource_id) DO UPDATE SET
                        state = excluded.state, observed_bytes = 0, checked_at = excluded.checked_at,
                        deletion_run_id = excluded.deletion_run_id, deletion_reason = excluded.deletion_reason
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, row.kind.name)
                    statement.setString(2, row.resourceId)
                    statement.setString(3, availabilityState)
                    statement.setString(4, now())
                    statement.setString(5, cleanupRunId)
                    statement.executeUpdate()
                }
                when (row.kind) {
                    CleanupResourceKind.JOB_ARTIFACT -> connection.prepareStatement(
                        "UPDATE artifacts SET content_path = NULL WHERE artifact_id = ?",
                    ).use { statement -> statement.setString(1, row.resourceId); statement.executeUpdate() }
                    CleanupResourceKind.JOB_LOG -> connection.prepareStatement(
                        "DELETE FROM log_entries WHERE job_id = ?",
                    ).use { statement -> statement.setString(1, row.resourceId); statement.executeUpdate() }
                    else -> Unit
                }
            }
        }
    }

    private fun finalizeCleanup(cleanupRunId: String): CleanupRunResponse {
        transaction { connection ->
            val results = cleanupItems(connection, cleanupRunId).filter { it.result != null }
            val released = results.fold(0L) { total, item -> Math.addExact(total, item.releasedBytes) }
            val state = if (results.all { it.result in setOf("DELETED", "ALREADY_MISSING") }) {
                CleanupRunState.COMPLETE
            } else {
                CleanupRunState.PARTIAL
            }
            connection.prepareStatement(
                "UPDATE cleanup_runs SET state = ?, released_bytes = ?, finished_at = ? WHERE cleanup_run_id = ?",
            ).use { statement ->
                statement.setString(1, state.name)
                statement.setLong(2, released)
                statement.setString(3, now())
                statement.setString(4, cleanupRunId)
                check(statement.executeUpdate() == 1)
            }
        }
        return connection().use { connection -> cleanupRun(connection, cleanupRunId, null) }
    }

    private fun markCleanupReconciliation(cleanupRunId: String, operationId: String) {
        transaction { connection ->
            connection.prepareStatement(
                "UPDATE cleanup_runs SET state = 'RECONCILIATION_REQUIRED', finished_at = ? WHERE cleanup_run_id = ?",
            ).use { statement -> statement.setString(1, now()); statement.setString(2, cleanupRunId); statement.executeUpdate() }
            connection.prepareStatement(
                """
                UPDATE operations SET state = 'RECONCILIATION_REQUIRED', reason_code = 'PERSISTENCE_UNCERTAIN',
                    reason_message = 'Cleanup bytes require reconciliation with persisted availability.', updated_at = ?
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement -> statement.setString(1, now()); statement.setString(2, operationId); statement.executeUpdate() }
        }
    }

    private fun requireExecutablePreview(
        previewId: String,
        principalId: String,
        itemIds: List<String>,
    ): CleanupPreviewState = connection().use { connection ->
        connection.prepareStatement(
            "SELECT * FROM cleanup_runs WHERE preview_id = ? AND principal_id = ?",
        ).use { statement ->
            statement.setString(1, previewId)
            statement.setString(2, principalId)
            statement.executeQuery().use { run ->
                if (!run.next()) throw resourceNotFound()
                if (run.getString("state") != CleanupRunState.PREVIEWED.name) {
                    throw ApiException.conflict("CLEANUP_ALREADY_EXECUTED", "The cleanup preview was already executed.")
                }
                if (run.getInt("truncated") != 0) {
                    throw ApiException.conflict("PREVIEW_INCOMPLETE", "A truncated cleanup preview cannot be executed.")
                }
                if (!Instant.parse(run.getString("expires_at")).isAfter(Instant.now(clock))) {
                    throw ApiException.conflict("PREVIEW_EXPIRED", "The cleanup preview has expired.")
                }
                val items = cleanupItems(connection, run.getString("cleanup_run_id"))
                if (itemIds.any { id -> items.none { it.itemId == id } }) {
                    throw ApiException.badRequest("INVALID_REQUEST", "The request contains an item outside the cleanup preview.")
                }
                CleanupPreviewState(run.getString("cleanup_run_id"), items)
            }
        }
    }

    private fun cleanupItems(connection: Connection, cleanupRunId: String): List<CleanupItemRow> =
        connection.prepareStatement(
            "SELECT * FROM cleanup_items WHERE cleanup_run_id = ? ORDER BY resource_kind, resource_id, item_id",
        ).use { statement ->
            statement.setString(1, cleanupRunId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            CleanupItemRow(
                                itemId = rows.getString("item_id"),
                                kind = CleanupResourceKind.valueOf(rows.getString("resource_kind")),
                                resourceId = rows.getString("resource_id"),
                                observedBytes = rows.getLong("observed_bytes"),
                                observedToken = rows.getString("observed_token"),
                                eligibleAt = rows.getString("eligible_at"),
                                protections = rows.getString("protection_reasons")
                                    .split(',').filter(String::isNotBlank),
                                result = rows.getString("result"),
                                releasedBytes = rows.getLong("released_bytes"),
                                reasonCode = rows.getString("reason_code"),
                                reasonMessage = rows.getString("reason_message"),
                            ),
                        )
                    }
                }
            }
        }

    private fun cleanupRun(
        connection: Connection,
        cleanupRunId: String,
        principalId: String?,
    ): CleanupRunResponse {
        val sql = if (principalId == null) {
            "SELECT * FROM cleanup_runs WHERE cleanup_run_id = ?"
        } else {
            "SELECT * FROM cleanup_runs WHERE cleanup_run_id = ? AND principal_id = ?"
        }
        return connection.prepareStatement(sql).use { statement ->
            statement.setString(1, cleanupRunId)
            if (principalId != null) statement.setString(2, principalId)
            statement.executeQuery().use { run ->
                if (!run.next()) throw resourceNotFound()
                CleanupRunResponse(
                    cleanupRunId = cleanupRunId,
                    previewId = run.getString("preview_id"),
                    state = run.getString("state"),
                    releasedBytes = run.getLong("released_bytes").toString(),
                    items = cleanupItems(connection, cleanupRunId)
                        .filter { it.result != null }
                        .map { item ->
                            CleanupItemResultResponse(
                                itemId = item.itemId,
                                result = requireNotNull(item.result),
                                releasedBytes = item.releasedBytes.toString(),
                                reason = item.reasonCode?.let { V2PublicReason(it, requireNotNull(item.reasonMessage)) },
                            )
                        },
                    startedAt = run.getString("started_at"),
                    finishedAt = run.getString("finished_at"),
                )
            }
        }
    }

    private fun hasActiveProtection(connection: Connection, kind: RetentionResourceKind, id: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM retention_holds WHERE resource_kind = ? AND resource_id = ? AND state = 'ACTIVE'",
        ).use { statement ->
            statement.setString(1, kind.name)
            statement.setString(2, id)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun hasActiveReservation(connection: Connection, kind: RetentionResourceKind, id: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM storage_reservations WHERE resource_kind = ? AND resource_id = ? AND state = 'ACTIVE'",
        ).use { statement ->
            statement.setString(1, kind.name)
            statement.setString(2, id)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun confinedStatePath(relative: String): Path {
        val candidate = stateDirectory.resolve(relative).toAbsolutePath().normalize()
        val root = stateDirectory.toAbsolutePath().normalize()
        if (!candidate.startsWith(root)) throw ApiException.conflict("RESOURCE_PATH_UNSAFE", "Stored resource metadata is outside its owner root.")
        return candidate
    }

    private fun token(kind: CleanupResourceKind, id: String, state: String): String =
        sha256("${kind.name}|$id|$state".toByteArray())

    private fun publicProtectionMessage(code: String): String = when (code) {
        "ACTIVE_JOB" -> "The resource belongs to an active job."
        "AWAITING_REVIEW" -> "The resource belongs to a job awaiting review."
        "SANDBOX_CLEANUP_PENDING" -> "Sandbox resource cleanup has not been confirmed."
        "RETENTION_HOLD" -> "The resource is protected by an active retention hold."
        "ACTIVE_RESERVATION" -> "The resource is protected by an active storage reservation."
        else -> "The resource changed after the cleanup preview."
    }

    private fun initializeDefaults() {
        transaction { connection ->
            val current = now()
            connection.prepareStatement(
                "INSERT OR IGNORE INTO runner_identity(singleton, runner_id, created_at) VALUES(1, ?, ?)",
            ).use { statement ->
                statement.setString(1, UUID.randomUUID().toString())
                statement.setString(2, current)
                statement.executeUpdate()
            }
            listOf(
                StorageArea.RUNNER_JOB to JOB_BUDGET_BYTES,
                StorageArea.RUNNER_TOOLCHAIN to TOOLCHAIN_BUDGET_BYTES,
            ).forEach { (area, budget) ->
                connection.prepareStatement(
                    """
                    INSERT OR IGNORE INTO storage_settings(area, budget_bytes, warning_percent, updated_at)
                    VALUES(?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, area.name)
                    statement.setLong(2, budget)
                    statement.setInt(3, WARNING_PERCENT)
                    statement.setString(4, current)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun reconcileInterruptedOperations() {
        transaction { connection ->
            val interrupted = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT operation_id, operation_kind, state FROM operations WHERE state IN ('RESERVED', 'APPLYING')",
                ).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(InterruptedOperation(rows.getString(1), rows.getString(2), rows.getString(3)))
                        }
                    }
                }
            }
            interrupted.forEach { operation ->
                if (operation.state == V2OperationState.RESERVED.name) {
                    rejectInterrupted(connection, operation.operationId, "INTERRUPTED_BEFORE_APPLY")
                    return@forEach
                }
                when (operation.kind) {
                    OP_HOLD_CREATE -> reconcileRowOperation(
                        connection,
                        operation.operationId,
                        "SELECT hold_id FROM retention_holds WHERE created_operation_id = ?",
                        "RETENTION_HOLD",
                    )
                    OP_HOLD_RELEASE -> reconcileRowOperation(
                        connection,
                        operation.operationId,
                        "SELECT hold_id FROM retention_holds WHERE released_operation_id = ?",
                        "RETENTION_HOLD",
                    )
                    OP_RESERVATION_CREATE -> reconcileRowOperation(
                        connection,
                        operation.operationId,
                        "SELECT reservation_id FROM storage_reservations WHERE created_operation_id = ?",
                        "STORAGE_RESERVATION",
                    )
                    OP_RESERVATION_RELEASE -> reconcileRowOperation(
                        connection,
                        operation.operationId,
                        "SELECT reservation_id FROM storage_reservations WHERE released_operation_id = ?",
                        "STORAGE_RESERVATION",
                    )
                    OP_CLEANUP_PREVIEW -> reconcileRowOperation(
                        connection,
                        operation.operationId,
                        "SELECT preview_id FROM cleanup_runs WHERE created_operation_id = ?",
                        "CLEANUP_PREVIEW",
                    )
                    OP_CLEANUP_EXECUTE -> reconcileInterruptedCleanup(connection, operation.operationId)
                    else -> requireReconciliation(
                        connection,
                        operation.operationId,
                        "UNKNOWN_OPERATION_KIND",
                        "The interrupted operation kind cannot be reconciled safely.",
                    )
                }
            }
        }
    }

    private fun reconcileRowOperation(
        connection: Connection,
        operationId: String,
        query: String,
        resultType: String,
    ) {
        val resourceId = connection.prepareStatement(query).use { statement ->
            statement.setString(1, operationId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        if (resourceId == null) {
            rejectInterrupted(connection, operationId, "INTERRUPTED_WITHOUT_SIDE_EFFECT")
        } else {
            completeInterrupted(connection, operationId, resultType, resourceId)
        }
    }

    private fun reconcileInterruptedCleanup(connection: Connection, operationId: String) {
        val run = connection.prepareStatement(
            "SELECT cleanup_run_id, state FROM cleanup_runs WHERE execute_operation_id = ?",
        ).use { statement ->
            statement.setString(1, operationId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to rows.getString(2) else null
            }
        }
        if (run == null) {
            rejectInterrupted(connection, operationId, "INTERRUPTED_WITHOUT_SIDE_EFFECT")
            return
        }
        if (run.second in setOf(CleanupRunState.COMPLETE.name, CleanupRunState.PARTIAL.name)) {
            completeInterrupted(connection, operationId, "CLEANUP_RUN", run.first)
            return
        }
        val selected = connection.prepareStatement(
            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN result IS NULL THEN 1 ELSE 0 END), 0) " +
                "FROM cleanup_items WHERE cleanup_run_id = ? AND selected = 1",
        ).use { statement ->
            statement.setString(1, run.first)
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) to rows.getLong(2) }
        }
        if (selected.first > 0 && selected.second == 0L) {
            val rows = cleanupItems(connection, run.first).filter { it.result != null }
            val released = rows.fold(0L) { total, item -> Math.addExact(total, item.releasedBytes) }
            val state = if (rows.all { it.result in setOf("DELETED", "ALREADY_MISSING") }) {
                CleanupRunState.COMPLETE
            } else {
                CleanupRunState.PARTIAL
            }
            connection.prepareStatement(
                "UPDATE cleanup_runs SET state = ?, released_bytes = ?, finished_at = ? WHERE cleanup_run_id = ?",
            ).use { statement ->
                statement.setString(1, state.name)
                statement.setLong(2, released)
                statement.setString(3, now())
                statement.setString(4, run.first)
                check(statement.executeUpdate() == 1)
            }
            completeInterrupted(connection, operationId, "CLEANUP_RUN", run.first)
            return
        }
        connection.prepareStatement(
            "UPDATE cleanup_runs SET state = 'RECONCILIATION_REQUIRED', finished_at = ? WHERE cleanup_run_id = ?",
        ).use { statement ->
            statement.setString(1, now())
            statement.setString(2, run.first)
            check(statement.executeUpdate() == 1)
        }
        requireReconciliation(
            connection,
            operationId,
            "INTERRUPTED_CLEANUP",
            "Cleanup was interrupted after its durable intent was applied.",
        )
    }

    private fun rejectInterrupted(connection: Connection, operationId: String, code: String) {
        connection.prepareStatement(
            "UPDATE operations SET state = 'REJECTED', reason_code = ?, reason_message = ?, updated_at = ? " +
                "WHERE operation_id = ?",
        ).use { statement ->
            statement.setString(1, code)
            statement.setString(2, "The operation was interrupted before a committed side effect was found.")
            statement.setString(3, now())
            statement.setString(4, operationId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun completeInterrupted(connection: Connection, operationId: String, resultType: String, resourceId: String) {
        connection.prepareStatement(
            "UPDATE operations SET state = 'COMPLETED', result_type = ?, result_id = ?, updated_at = ? " +
                "WHERE operation_id = ?",
        ).use { statement ->
            statement.setString(1, resultType)
            statement.setString(2, resourceId)
            statement.setString(3, now())
            statement.setString(4, operationId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun requireReconciliation(
        connection: Connection,
        operationId: String,
        code: String,
        message: String,
    ) {
        connection.prepareStatement(
            "UPDATE operations SET state = 'RECONCILIATION_REQUIRED', reason_code = ?, reason_message = ?, " +
                "updated_at = ? WHERE operation_id = ?",
        ).use { statement ->
            statement.setString(1, code)
            statement.setString(2, message)
            statement.setString(3, now())
            statement.setString(4, operationId)
            check(statement.executeUpdate() == 1)
        }
    }

    private fun reserveOperation(
        principalId: String,
        kind: String,
        idempotencyKey: String,
        normalizedRequest: JsonObject,
    ): OperationReceipt {
        val requestSha = requestSha256(kind, normalizedRequest)
        return transaction { connection ->
            findOperation(connection, principalId, kind, idempotencyKey)?.let { existing ->
                if (existing.requestSha256 != requestSha) {
                    throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT",
                        "The idempotency key is already bound to a different request.",
                    )
                }
                return@transaction OperationReceipt(true, existing)
            }
            val operationId = UUID.randomUUID().toString()
            val current = now()
            connection.prepareStatement(
                """
                INSERT INTO operations (
                    operation_id, principal_id, operation_kind, idempotency_key, request_sha256,
                    contract_id, contract_version, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'storage-retention', 1, 'RESERVED', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operationId)
                statement.setString(2, principalId)
                statement.setString(3, kind)
                statement.setString(4, idempotencyKey)
                statement.setString(5, requestSha)
                statement.setString(6, current)
                statement.setString(7, current)
                check(statement.executeUpdate() == 1)
            }
            OperationReceipt(
                false,
                V2OperationResponse(
                    operationId = operationId,
                    state = V2OperationState.RESERVED,
                    kind = kind,
                    requestSha256 = requestSha,
                    createdAt = current,
                    updatedAt = current,
                ),
            )
        }
    }

    private fun existingOperation(
        principalId: String,
        kind: String,
        idempotencyKey: String,
        normalizedRequest: JsonObject,
    ): OperationReceipt? {
        val expectedHash = requestSha256(kind, normalizedRequest)
        return connection().use { connection ->
            findOperation(connection, principalId, kind, idempotencyKey)?.let { existing ->
                if (existing.requestSha256 != expectedHash) {
                    throw ApiException.conflict(
                        "IDEMPOTENCY_CONFLICT",
                        "The idempotency key is already bound to a different request.",
                    )
                }
                OperationReceipt(true, existing)
            }
        }
    }

    private fun transitionOperation(operationId: String, state: V2OperationState) {
        transaction { connection ->
            connection.prepareStatement("UPDATE operations SET state = ?, updated_at = ? WHERE operation_id = ?").use {
                it.setString(1, state.name)
                it.setString(2, now())
                it.setString(3, operationId)
                check(it.executeUpdate() == 1)
            }
        }
    }

    private fun completeOperation(operationId: String, type: String, resourceId: String, resultJson: String?) {
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE operations SET state = 'COMPLETED', result_type = ?, result_id = ?,
                    result_json = ?, updated_at = ? WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, type)
                statement.setString(2, resourceId)
                statement.setString(3, resultJson)
                statement.setString(4, now())
                statement.setString(5, operationId)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    private fun rejectOperation(operationId: String, code: String, message: String) {
        transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE operations SET state = 'REJECTED', reason_code = ?, reason_message = ?, updated_at = ?
                WHERE operation_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, code)
                statement.setString(2, message)
                statement.setString(3, now())
                statement.setString(4, operationId)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    private fun findOperation(
        connection: Connection,
        principalId: String,
        kind: String,
        key: String,
    ): V2OperationResponse? = connection.prepareStatement(
        "SELECT * FROM operations WHERE principal_id = ? AND operation_kind = ? AND idempotency_key = ?",
    ).use { statement ->
        statement.setString(1, principalId)
        statement.setString(2, kind)
        statement.setString(3, key)
        statement.executeQuery().use { rows -> if (rows.next()) rows.toOperationResponse() else null }
    }

    private fun ResultSet.toOperationResponse(): V2OperationResponse = V2OperationResponse(
        operationId = getString("operation_id"),
        state = V2OperationState.valueOf(getString("state")),
        kind = getString("operation_kind"),
        requestSha256 = getString("request_sha256"),
        result = getString("result_type")?.let { type -> V2OperationResult(type, getString("result_id")) },
        reason = getString("reason_code")?.let { code -> V2PublicReason(code, getString("reason_message")) },
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

    private fun requestSha256(kind: String, request: JsonObject): String {
        val effective = kotlinx.serialization.json.buildJsonObject {
            put("foundationContractVersion", kotlinx.serialization.json.JsonPrimitive(1))
            put("operationContractVersion", kotlinx.serialization.json.JsonPrimitive(1))
            put("operationKind", kotlinx.serialization.json.JsonPrimitive(kind))
            put("request", request)
        }
        return sha256(JsonCanonicalizer(effective.toString()).encodedUTF8)
    }

    private fun appendTarget(request: JsonObject, key: String, value: String): JsonObject =
        JsonObject(request + (key to kotlinx.serialization.json.JsonPrimitive(value)))

    private fun activeHoldId(connection: Connection, principalId: String, command: HoldCreateCommand): String? =
        connection.prepareStatement(
            """
            SELECT hold_id FROM retention_holds
            WHERE principal_id = ? AND resource_kind = ? AND resource_id = ? AND reason = ?
                AND client_reference_type = ? AND client_reference_id = ? AND state = 'ACTIVE'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, principalId)
            statement.setString(2, command.resourceKind.name)
            statement.setString(3, command.resourceId)
            statement.setString(4, command.reason)
            statement.setString(5, command.clientReferenceType)
            statement.setString(6, command.clientReferenceId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun requireResourceExists(kind: RetentionResourceKind, resourceId: String) {
        val exists = connection().use { connection ->
            val sql = when (kind) {
                RetentionResourceKind.JOB -> "SELECT 1 FROM jobs WHERE job_id = ?"
                RetentionResourceKind.ARTIFACT -> "SELECT 1 FROM artifacts WHERE artifact_id = ?"
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, resourceId)
                statement.executeQuery().use(ResultSet::next)
            }
        }
        if (!exists) throw resourceNotFound()
    }

    private fun requireOwnedActiveHold(principalId: String, holdId: String) {
        val exists = connection().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM retention_holds WHERE hold_id = ? AND principal_id = ? AND state = 'ACTIVE'",
            ).use { statement ->
                statement.setString(1, holdId)
                statement.setString(2, principalId)
                statement.executeQuery().use(ResultSet::next)
            }
        }
        if (!exists) throw resourceNotFound()
    }

    private fun requireOwnedActiveReservation(principalId: String, reservationId: String) {
        val exists = connection().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM storage_reservations WHERE reservation_id = ? AND principal_id = ? AND state = 'ACTIVE'",
            ).use { statement ->
                statement.setString(1, reservationId)
                statement.setString(2, principalId)
                statement.executeQuery().use(ResultSet::next)
            }
        }
        if (!exists) throw resourceNotFound()
    }

    private fun ensureNoActiveReservation(principalId: String, command: ReservationCreateCommand) {
        val exists = connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT 1 FROM storage_reservations
                WHERE principal_id = ? AND area = ? AND purpose = ? AND resource_kind = ?
                    AND resource_id = ? AND state = 'ACTIVE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, principalId)
                statement.setString(2, command.area.name)
                statement.setString(3, command.purpose)
                statement.setString(4, command.resourceKind.name)
                statement.setString(5, command.resourceId)
                statement.executeQuery().use(ResultSet::next)
            }
        }
        if (exists) throw ApiException.conflict("RESERVATION_EXISTS", "An active reservation already exists for the resource.")
    }

    private fun ensureNoReconciliationRequired() {
        val blocked = connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT 1 FROM operations WHERE state = 'RECONCILIATION_REQUIRED' LIMIT 1",
                ).use(ResultSet::next)
            }
        }
        if (blocked) {
            throw ApiException.serviceUnavailable(
                "RECONCILIATION_REQUIRED",
                "A prior storage operation must be reconciled before new storage side effects can start.",
            )
        }
    }

    private fun ensureCapacity(area: StorageArea, requested: Long) {
        val summary = storageSummary().areas.single { it.area == area }
        if (summary.measurementState != StorageMeasurementState.COMPLETE || summary.state == StorageAreaState.STORAGE_UNAVAILABLE) {
            throw ApiException.serviceUnavailable("STORAGE_UNAVAILABLE", "Storage usage could not be measured safely.")
        }
        val used = summary.usedBytes.toLong()
        val reserved = summary.reservedBytes.toLong()
        val budget = summary.budgetBytes.toLong()
        val total = try {
            Math.addExact(Math.addExact(used, reserved), requested)
        } catch (_: ArithmeticException) {
            throw ApiException.conflict("STORAGE_BUDGET_EXCEEDED", "The requested reservation exceeds the storage budget.")
        }
        val requiredSpace = runCatching { Math.addExact(requested, RECOVERY_RESERVE_BYTES) }.getOrElse { Long.MAX_VALUE }
        if (total > budget || summary.usableBytes.toLong() < requiredSpace) {
            throw ApiException.conflict("STORAGE_BUDGET_EXCEEDED", "The requested reservation exceeds the storage budget or usable space.")
        }
    }

    private fun storageSettings(): Map<StorageArea, Pair<Long, Int>> = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT area, budget_bytes, warning_percent FROM storage_settings").use { rows ->
                buildMap {
                    while (rows.next()) put(StorageArea.valueOf(rows.getString(1)), rows.getLong(2) to rows.getInt(3))
                }
            }
        }
    }

    private fun activeReservationBytes(): Map<StorageArea, Long> = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT area, COALESCE(SUM(requested_bytes), 0) FROM storage_reservations WHERE state = 'ACTIVE' GROUP BY area",
            ).use { rows ->
                buildMap {
                    while (rows.next()) put(StorageArea.valueOf(rows.getString(1)), rows.getLong(2))
                }
            }
        }
    }

    private fun measureRoots(roots: List<Path>): Measurement {
        var bytes = 0L
        var entries = 0
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        return try {
            for (root in roots) {
                if (!root.exists(LinkOption.NOFOLLOW_LINKS)) continue
                if (!root.toAbsolutePath().normalize().startsWith(stateDirectory.toAbsolutePath().normalize())) {
                    return Measurement(0, StorageMeasurementState.FAILED)
                }
                Files.walkFileTree(root, setOf(), MAX_WALK_DEPTH, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        checkBudget(attributes)
                        if (attributes.isSymbolicLink) throw UnsafeStorageEntry()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        checkBudget(attributes)
                        if (attributes.isSymbolicLink || !attributes.isRegularFile) throw UnsafeStorageEntry()
                        bytes = Math.addExact(bytes, attributes.size())
                        return FileVisitResult.CONTINUE
                    }

                    private fun checkBudget(attributes: BasicFileAttributes) {
                        entries++
                        if (entries > MAX_WALK_ENTRIES || System.nanoTime() > deadline) throw WalkIncomplete()
                    }
                })
            }
            Measurement(bytes, StorageMeasurementState.COMPLETE)
        } catch (_: WalkIncomplete) {
            Measurement(bytes, StorageMeasurementState.INCOMPLETE)
        } catch (_: Throwable) {
            Measurement(bytes, StorageMeasurementState.FAILED)
        }
    }

    private fun reachesWarning(used: Long, reserved: Long, budget: Long, warningPercent: Int): Boolean {
        val total = runCatching { Math.addExact(used, reserved) }.getOrElse { return true }
        return total > 0 && total * 100.0 >= budget * warningPercent.toDouble()
    }

    private fun <T> transaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath").apply {
        createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    private fun now(): String = Instant.now(clock).toString()

    private fun resourceNotFound() = ApiException(HttpStatusCode.NotFound, "RESOURCE_NOT_FOUND", "The requested resource does not exist.")

    private data class Measurement(val bytes: Long, val state: StorageMeasurementState)
    private class WalkIncomplete : RuntimeException()
    private class UnsafeStorageEntry : RuntimeException()

    private companion object {
        const val OP_HOLD_CREATE = "retention-hold-create"
        const val OP_HOLD_RELEASE = "retention-hold-release"
        const val OP_RESERVATION_CREATE = "storage-reservation-create"
        const val OP_RESERVATION_RELEASE = "storage-reservation-release"
        const val OP_CLEANUP_PREVIEW = "cleanup-preview-create"
        const val OP_CLEANUP_EXECUTE = "cleanup-execute"
        const val JOB_BUDGET_BYTES = 64L * 1024 * 1024 * 1024
        const val TOOLCHAIN_BUDGET_BYTES = 32L * 1024 * 1024 * 1024
        const val RECOVERY_RESERVE_BYTES = 64L * 1024 * 1024
        const val WARNING_PERCENT = 80
        const val MAX_WALK_ENTRIES = 100_000
        const val MAX_WALK_DEPTH = 16
        const val MAX_PREVIEW_ITEMS = 1_000
    }

    private val JOB_ROOTS: List<Path>
        get() = listOf("workspaces", "artifacts", "logs", "manifests", "sandbox-imports")
            .map { stateDirectory.resolve(it) }
    private val TOOLCHAIN_ROOTS: List<Path>
        get() = listOf("toolchains", "toolchain-staging").map { stateDirectory.resolve(it) }

    private data class ResourceCandidate(
        val itemId: String,
        val kind: CleanupResourceKind,
        val resourceId: String,
        val path: Path,
        val eligibleAt: String,
        val observation: ResourceObservation,
        val protections: Set<String>,
    )

    private data class ResourceObservation(
        val bytes: Long,
        val token: String,
        val unsafe: Boolean,
        val exists: Boolean,
    )

    private data class CleanupItemRow(
        val itemId: String,
        val kind: CleanupResourceKind,
        val resourceId: String,
        val observedBytes: Long,
        val observedToken: String,
        val eligibleAt: String,
        val protections: List<String>,
        val result: String?,
        val releasedBytes: Long,
        val reasonCode: String?,
        val reasonMessage: String?,
    )

    private data class CleanupPreviewState(
        val cleanupRunId: String,
        val items: List<CleanupItemRow>,
    )

    private data class ItemExecutionResult(
        val result: CleanupItemResult,
        val releasedBytes: Long,
        val reasonCode: String?,
        val reasonMessage: String?,
    )

    private data class InterruptedOperation(
        val operationId: String,
        val kind: String,
        val state: String,
    )
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte) }
