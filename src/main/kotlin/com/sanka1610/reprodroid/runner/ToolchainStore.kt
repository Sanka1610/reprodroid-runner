package com.sanka1610.reprodroid.runner

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.erdtman.jcs.JsonCanonicalizer
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.io.path.exists

internal class ToolchainStore(
    private val stateDirectory: Path,
    private val runnerId: String,
    private val catalog: ToolchainCatalog,
    private val installer: ToolchainInstaller = ToolchainInstaller(stateDirectory),
) : AutoCloseable {
    private val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "reprodroid-toolchain-worker").apply { isDaemon = true } }
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }

    init {
        reconcileInventory()
    }

    fun catalog(): ToolchainCatalogResponse = catalog.response

    @Synchronized
    fun plan(request: ResolveToolchainPlanRequest, principalId: String = "local-development"): ToolchainPlanResponse {
        val artifacts = catalog.resolve(request.requirements)
        val installed = inventoryIds()
        val items = artifacts.map { artifact ->
            ToolchainPlanItem(
                artifact.artifactId,
                artifact.component,
                artifact.version,
                artifact.archiveSha256,
                if (artifact.artifactId in installed) "0" else artifact.archiveSizeBytes,
                if (artifact.artifactId in installed) "0" else reservationBytes(artifact).toString(),
                artifact.artifactId in installed,
            )
        }
        val requiredLicenseIds = artifacts
            .filterNot { it.artifactId in installed }
            .map { it.licenseId }
            .distinct()
            .filterNot { licenseId -> hasCurrentLicenseAcceptance(principalId, catalog.license(licenseId)) }
            .sorted()
        val licenses = requiredLicenseIds.map(catalog::license)
        val normalizedRequirements = request.requirements.sortedWith(compareBy({ it.component.name }, { it.version }))
        val planSha = canonicalSha(
            json.encodeToString(
                PlanDigestInput(catalog.response.catalogSha256, catalog.response.platform, normalizedRequirements, items.map(ToolchainPlanItem::artifactId).sorted()),
            ),
        )
        return ToolchainPlanResponse(
            runnerId = runnerId,
            catalogSha256 = catalog.response.catalogSha256,
            planSha256 = planSha,
            platform = catalog.response.platform,
            items = items,
            requiredLicenses = licenses,
            downloadBytes = items.sumOf { it.downloadBytes.toLong() }.toString(),
            reservedBytes = items.sumOf { it.reservedBytes.toLong() }.toString(),
        )
    }

    @Synchronized
    fun create(request: CreateToolchainInstallationRequest, principalId: String, idempotencyKey: String): ToolchainInstallationResponse {
        requireIdempotencyKey(idempotencyKey)
        val plan = plan(ResolveToolchainPlanRequest(request.requirements), principalId)
        if (request.catalogSha256 != catalog.response.catalogSha256 || request.planSha256 != plan.planSha256) {
            throw ApiException.conflict("TOOLCHAIN_PLAN_STALE", "The plan or catalog digest is no longer current.")
        }
        val acceptanceById = request.licenseAcceptances.associateBy(ToolchainLicenseAcceptanceRequest::licenseId)
        if (acceptanceById.size != request.licenseAcceptances.size) {
            throw ApiException.badRequest("INVALID_REQUEST", "licenseAcceptances must not contain duplicate licenseId values.")
        }
        plan.requiredLicenses.forEach { license ->
            val acceptance = acceptanceById[license.licenseId]
            if (acceptance?.accepted != true || acceptance.licenseTextSha256 != license.textSha256) {
                throw ApiException.conflict("LICENSE_ACCEPTANCE_REQUIRED", "Current consent is required for ${license.licenseId}.")
            }
        }
        if (acceptanceById.keys != plan.requiredLicenses.map { it.licenseId }.toSet()) {
            throw ApiException.badRequest("INVALID_REQUEST", "licenseAcceptances must exactly match requiredLicenses.")
        }
        val requestJson = json.encodeToString(request)
        val requestSha = canonicalSha(requestJson)
        connection().use { connection ->
            existingByIdempotency(connection, principalId, "toolchain-install", idempotencyKey)?.let { existing ->
                if (existing.requestSha256 != requestSha) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request.")
                return installation(existing.resourceId, principalId)
            }
            val now = Instant.now().toString()
            val operationId = uuid()
            val installationId = uuid()
            connection.autoCommit = false
            try {
                insertOperation(connection, operationId, principalId, "toolchain-install", idempotencyKey, requestSha, installationId, now)
                connection.prepareStatement(
                    "INSERT INTO toolchain_installations(installation_id,operation_id,principal_id,plan_sha256,catalog_sha256,request_json,state,progress_percent,cancel_requested,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,0,?,?)",
                ).use { statement ->
                    statement.setString(1, installationId); statement.setString(2, operationId); statement.setString(3, principalId)
                    statement.setString(4, plan.planSha256); statement.setString(5, plan.catalogSha256); statement.setString(6, requestJson)
                    statement.setString(7, ToolchainInstallationState.PLANNED.name); statement.setInt(8, 0)
                    statement.setString(9, now); statement.setString(10, now); statement.executeUpdate()
                }
                val artifacts = catalog.resolve(request.requirements)
                connection.prepareStatement(
                    "INSERT INTO toolchain_installation_items(installation_id,ordinal,artifact_id,component,version,state,downloaded_bytes) VALUES(?,?,?,?,?,?,0)",
                ).use { statement ->
                    artifacts.forEachIndexed { ordinal, artifact ->
                        statement.setString(1, installationId); statement.setInt(2, ordinal); statement.setString(3, artifact.artifactId)
                        statement.setString(4, artifact.component.name); statement.setString(5, artifact.version)
                        statement.setString(6, ToolchainInstallationState.PLANNED.name); statement.addBatch()
                    }
                    statement.executeBatch()
                }
                request.licenseAcceptances.forEach { acceptance ->
                    connection.prepareStatement(
                        "INSERT OR REPLACE INTO toolchain_license_acceptances(runner_id,principal_id,license_id,license_text_sha256,accepted_at) VALUES(?,?,?,?,?)",
                    ).use { statement ->
                        statement.setString(1, runnerId); statement.setString(2, principalId); statement.setString(3, acceptance.licenseId)
                        statement.setString(4, acceptance.licenseTextSha256); statement.setString(5, now); statement.executeUpdate()
                    }
                }
                connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
            worker.submit { process(installationId) }
            return installation(installationId, principalId)
        }
    }

    @Synchronized
    fun installation(installationId: String, principalId: String? = null): ToolchainInstallationResponse = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM toolchain_installations WHERE installation_id=?" + if (principalId == null) "" else " AND principal_id=?").use { statement ->
            statement.setString(1, installationId)
            if (principalId != null) statement.setString(2, principalId)
            statement.executeQuery().use { row ->
                if (!row.next()) throw ApiException.notFound("TOOLCHAIN_INSTALLATION_NOT_FOUND", "The requested installation does not exist.")
                val items = connection.prepareStatement("SELECT * FROM toolchain_installation_items WHERE installation_id=? ORDER BY ordinal").use { itemStatement ->
                    itemStatement.setString(1, installationId)
                    itemStatement.executeQuery().use { itemRows -> buildList {
                        while (itemRows.next()) add(
                            ToolchainInstallationItemResponse(
                                itemRows.getString("artifact_id"), ToolchainComponent.valueOf(itemRows.getString("component")),
                                itemRows.getString("version"), ToolchainInstallationState.valueOf(itemRows.getString("state")),
                                itemRows.getLong("downloaded_bytes").toString(),
                            ),
                        )
                    } }
                }
                ToolchainInstallationResponse(
                    installationId = installationId, operationId = row.getString("operation_id"), runnerId = runnerId,
                    planSha256 = row.getString("plan_sha256"), catalogSha256 = row.getString("catalog_sha256"),
                    state = ToolchainInstallationState.valueOf(row.getString("state")), progressPercent = row.getInt("progress_percent"),
                    items = items,
                    reason = row.getString("reason_code")?.let { V2PublicReason(it, row.getString("reason_message")) },
                    createdAt = row.getString("created_at"), updatedAt = row.getString("updated_at"),
                )
            }
        }
    }

    @Synchronized
    fun cancel(installationId: String, principalId: String? = null): ToolchainInstallationResponse {
        val current = installation(installationId, principalId)
        if (current.state in TERMINAL_STATES) return current
        connection().use { connection ->
            connection.prepareStatement("UPDATE toolchain_installations SET cancel_requested=1,state=?,updated_at=? WHERE installation_id=?").use { statement ->
                statement.setString(1, ToolchainInstallationState.CANCEL_REQUESTED.name); statement.setString(2, Instant.now().toString())
                statement.setString(3, installationId); statement.executeUpdate()
            }
        }
        return installation(installationId, principalId)
    }

    @Synchronized
    fun inventory(): ToolchainInventoryResponse = connection().use { connection ->
        val items = connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM toolchain_inventory ORDER BY component,version").use { rows -> buildList {
            while (rows.next()) add(
                ToolchainInventoryItem(
                    rows.getString("artifact_id"), ToolchainComponent.valueOf(rows.getString("component")), rows.getString("version"),
                    rows.getString("archive_sha256"), rows.getString("content_manifest_sha256"), rows.getLong("installed_bytes").toString(),
                    ToolchainInventoryState.valueOf(rows.getString("state")), rows.getString("installed_at"),
                ),
            )
        } } }
        ToolchainInventoryResponse(runnerId = runnerId, catalogSha256 = catalog.response.catalogSha256, items = items)
    }

    @Synchronized
    fun removalPreview(request: ToolchainRemovalRequest, principalId: String, idempotencyKey: String): ToolchainRemovalPreviewResponse {
        requireIdempotencyKey(idempotencyKey)
        if (request.artifactIds.isEmpty() || request.artifactIds.size > 32 || request.artifactIds.distinct().size != request.artifactIds.size) {
            throw ApiException.badRequest("INVALID_REQUEST", "artifactIds must contain 1..32 distinct values.")
        }
        val ids = request.artifactIds.sorted()
        val requestSha = canonicalSha(json.encodeToString(ToolchainRemovalRequest(ids)))
        connection().use { connection ->
            connection.prepareStatement("SELECT preview_id,request_sha256 FROM toolchain_removal_previews WHERE principal_id=? AND idempotency_key=?").use { statement ->
                statement.setString(1, principalId); statement.setString(2, idempotencyKey)
                statement.executeQuery().use { row ->
                    if (row.next()) {
                        if (row.getString(2) != requestSha) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request.")
                        return removalPreview(row.getString(1), principalId)
                    }
                }
            }
        }
        val bytes = connection().use { connection -> ids.sumOf { id ->
            connection.prepareStatement("SELECT installed_bytes FROM toolchain_inventory WHERE artifact_id=? AND state='VERIFIED'").use { statement ->
                statement.setString(1, id); statement.executeQuery().use { row -> if (row.next()) row.getLong(1) else throw ApiException.notFound("TOOLCHAIN_NOT_INSTALLED", "$id is not installed.") }
            }
        } }
        val now = Instant.now(); val previewId = uuid(); val expiresAt = now.plus(15, ChronoUnit.MINUTES)
        connection().use { connection ->
            connection.autoCommit = false
            try {
            requireActivePrincipal(connection, principalId)
            connection.prepareStatement("INSERT INTO toolchain_removal_previews(preview_id,principal_id,artifact_ids_json,releasable_bytes,expires_at,created_at,idempotency_key,request_sha256) VALUES(?,?,?,?,?,?,?,?)").use { statement ->
            statement.setString(1, previewId); statement.setString(2, principalId); statement.setString(3, json.encodeToString(ids))
            statement.setLong(4, bytes); statement.setString(5, expiresAt.toString()); statement.setString(6, now.toString())
            statement.setString(7, idempotencyKey); statement.setString(8, requestSha); statement.executeUpdate()
            }
            connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        return ToolchainRemovalPreviewResponse(previewId = previewId, artifactIds = ids, releasableBytes = bytes.toString(), expiresAt = expiresAt.toString())
    }

    private fun removalPreview(previewId: String, principalId: String): ToolchainRemovalPreviewResponse = connection().use { connection ->
        connection.prepareStatement("SELECT artifact_ids_json,releasable_bytes,expires_at FROM toolchain_removal_previews WHERE preview_id=? AND principal_id=?").use { statement ->
            statement.setString(1, previewId); statement.setString(2, principalId)
            statement.executeQuery().use { row ->
                if (!row.next()) throw ApiException.notFound("TOOLCHAIN_REMOVAL_PREVIEW_NOT_FOUND", "The removal preview does not exist.")
                ToolchainRemovalPreviewResponse(
                    previewId = previewId,
                    artifactIds = json.decodeFromString(row.getString(1)),
                    releasableBytes = row.getLong(2).toString(),
                    expiresAt = row.getString(3),
                )
            }
        }
    }

    @Synchronized
    fun executeRemoval(request: ExecuteToolchainRemovalRequest, principalId: String, idempotencyKey: String): V2OperationResponse {
        requireIdempotencyKey(idempotencyKey)
        val requestSha = canonicalSha(json.encodeToString(request))
        val existing = connection().use { connection -> existingByIdempotency(connection, principalId, "toolchain-remove", idempotencyKey) }
        existing?.let {
            if (it.requestSha256 != requestSha) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request.")
            return operation(it.operationId)
        }
        val ids = connection().use { connection ->
            val preview = connection.prepareStatement("SELECT artifact_ids_json,expires_at FROM toolchain_removal_previews WHERE preview_id=? AND principal_id=?").use { statement ->
                statement.setString(1, request.previewId); statement.setString(2, principalId)
                statement.executeQuery().use { row ->
                    if (!row.next()) throw ApiException.notFound("TOOLCHAIN_REMOVAL_PREVIEW_NOT_FOUND", "The removal preview does not exist.")
                    row.getString(1) to Instant.parse(row.getString(2))
                }
            }
            if (!preview.second.isAfter(Instant.now())) throw ApiException.conflict("TOOLCHAIN_REMOVAL_PREVIEW_EXPIRED", "The removal preview has expired.")
            json.decodeFromString<List<String>>(preview.first).also { artifactIds ->
                artifactIds.forEach { id ->
                    connection.prepareStatement("SELECT 1 FROM toolchain_inventory WHERE artifact_id=? AND state='VERIFIED'").use { statement ->
                        statement.setString(1, id)
                        statement.executeQuery().use { row -> if (!row.next()) throw ApiException.conflict("TOOLCHAIN_REMOVAL_STALE", "$id is no longer removable.") }
                    }
                }
            }
        }
        val operationId = uuid(); val now = Instant.now().toString()
        connection().use { connection ->
            connection.autoCommit = false
            try {
            insertOperation(connection, operationId, principalId, "toolchain-remove", idempotencyKey, requestSha, request.previewId, now)
            connection.prepareStatement("UPDATE operations SET state='APPLYING',updated_at=? WHERE operation_id=?").use { statement ->
                statement.setString(1, now); statement.setString(2, operationId); statement.executeUpdate()
            }
            connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        try {
            ids.forEach { id ->
                val relativePath = connection().use { connection -> connection.prepareStatement("SELECT relative_path FROM toolchain_inventory WHERE artifact_id=? AND state='VERIFIED'").use { statement ->
                    statement.setString(1, id); statement.executeQuery().use { row -> if (row.next()) row.getString(1) else throw ApiException.conflict("TOOLCHAIN_REMOVAL_STALE", "$id is no longer removable.") }
                } }
                installer.remove(relativePath)
                connection().use { connection -> connection.prepareStatement("DELETE FROM toolchain_inventory WHERE artifact_id=?").use { statement -> statement.setString(1, id); statement.executeUpdate() } }
            }
            connection().use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("UPDATE operations SET state='COMPLETED',updated_at=? WHERE operation_id=?").use { statement -> statement.setString(1, Instant.now().toString()); statement.setString(2, operationId); statement.executeUpdate() }
                    connection.prepareStatement("DELETE FROM toolchain_removal_previews WHERE preview_id=?").use { statement -> statement.setString(1, request.previewId); statement.executeUpdate() }
                    connection.commit()
                } catch (failure: Throwable) { connection.rollback(); throw failure }
            }
        } catch (failure: Throwable) {
            reconcileInventory()
            connection().use { connection -> connection.prepareStatement("UPDATE operations SET state='RECONCILIATION_REQUIRED',reason_code='TOOLCHAIN_REMOVAL_UNCERTAIN',reason_message='Toolchain removal requires inventory reconciliation.',updated_at=? WHERE operation_id=?").use { statement ->
                statement.setString(1, Instant.now().toString()); statement.setString(2, operationId); statement.executeUpdate()
            } }
            throw ApiException.serviceUnavailable("RECONCILIATION_REQUIRED", "Toolchain removal requires inventory reconciliation.")
        }
        return operation(operationId)
    }

    @Synchronized
    fun operation(operationId: String): V2OperationResponse = connection().use { connection ->
        connection.prepareStatement("SELECT * FROM operations WHERE operation_id=?").use { statement ->
            statement.setString(1, operationId); statement.executeQuery().use { row ->
                if (!row.next()) throw ApiException.notFound("OPERATION_NOT_FOUND", "The requested operation does not exist.")
                val state = V2OperationState.valueOf(row.getString("state"))
                V2OperationResponse(
                    row.getString("operation_id"), state, row.getString("operation_kind"), row.getString("request_sha256"),
                    if (state == V2OperationState.COMPLETED) V2OperationResult(row.getString("result_type"), row.getString("result_id")) else null,
                    if (state in setOf(V2OperationState.REJECTED, V2OperationState.RECONCILIATION_REQUIRED)) V2PublicReason(row.getString("reason_code"), row.getString("reason_message")) else null,
                    row.getString("created_at"), row.getString("updated_at"),
                )
            }
        }
    }

    fun startRecovery() {
        val ids = connection().use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT installation_id FROM toolchain_installations WHERE state NOT IN ('INSTALLED','CANCELLED','FAILED','RECONCILIATION_REQUIRED') ORDER BY created_at").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        } }
        ids.forEach { id -> worker.submit { installer.removeOwnedStaging(id); process(id) } }
    }

    private fun process(installationId: String) {
        try {
            val request = connection().use { connection -> connection.prepareStatement("SELECT request_json FROM toolchain_installations WHERE installation_id=?").use { statement ->
                statement.setString(1, installationId); statement.executeQuery().use { row -> if (!row.next()) return; json.decodeFromString<CreateToolchainInstallationRequest>(row.getString(1)) }
            } }
            val artifacts = catalog.resolve(request.requirements)
            val alreadyInstalled = inventoryIds()
            reserve(installationId, artifacts.filterNot { it.artifactId in alreadyInstalled }.sumOf(::reservationBytes))
            artifacts.forEachIndexed { index, artifact ->
                if (artifact.artifactId in alreadyInstalled) {
                    updateItem(installationId, artifact.artifactId, ToolchainInstallationState.INSTALLED, 0)
                    return@forEachIndexed
                }
                val installed = installer.install(installationId, artifact, { cancellationRequested(installationId) }) { state, bytes ->
                    updateItem(installationId, artifact.artifactId, state, bytes)
                    updateInstallation(installationId, state, ((index * 100L + minOf(99, bytes * 100 / artifact.archiveSizeBytes.toLong())) / artifacts.size).toInt())
                }
                persistInventory(installed)
                updateItem(installationId, artifact.artifactId, ToolchainInstallationState.INSTALLED, artifact.archiveSizeBytes.toLong())
            }
            finish(installationId, ToolchainInstallationState.INSTALLED, null, null)
        } catch (_: ToolchainCancelledException) {
            installer.removeOwnedStaging(installationId)
            finish(installationId, ToolchainInstallationState.CANCELLED, null, null)
        } catch (failure: ToolchainInstallFailure) {
            installer.removeOwnedStaging(installationId)
            val terminal = if (failure.code in setOf("TOOLCHAIN_DESTINATION_EXISTS", "ATOMIC_PUBLISH_UNSUPPORTED")) ToolchainInstallationState.RECONCILIATION_REQUIRED else ToolchainInstallationState.FAILED
            finish(installationId, terminal, failure.code, failure.message)
        } catch (failure: Throwable) {
            installer.removeOwnedStaging(installationId)
            finish(installationId, ToolchainInstallationState.FAILED, "TOOLCHAIN_INSTALL_FAILED", failure.message ?: "Toolchain installation failed.")
        }
    }

    @Synchronized
    private fun reserve(installationId: String, requested: Long) {
        if (cancellationRequested(installationId)) throw ToolchainCancelledException()
        updateInstallation(installationId, ToolchainInstallationState.RESERVING, 0)
        connection().use { connection ->
            val budget = connection.prepareStatement("SELECT budget_bytes FROM storage_settings WHERE area='RUNNER_TOOLCHAIN'").use { it.executeQuery().use { row -> if (row.next()) row.getLong(1) else 32L * 1024 * 1024 * 1024 } }
            val used = inventoryInstalledBytes(connection)
            val reserved = connection.createStatement().executeQuery("SELECT COALESCE(SUM(requested_bytes),0) FROM storage_reservations WHERE area='RUNNER_TOOLCHAIN' AND state='ACTIVE'").use { row -> row.next(); row.getLong(1) }
            val fileStore: FileStore = Files.getFileStore(stateDirectory)
            if (requested > budget - used - reserved || requested > fileStore.usableSpace) throw ToolchainInstallFailure("TOOLCHAIN_STORAGE_UNAVAILABLE", "The Runner cannot reserve enough toolchain storage.")
            val owner = connection.prepareStatement("SELECT operation_id,principal_id FROM toolchain_installations WHERE installation_id=?").use { statement ->
                statement.setString(1, installationId); statement.executeQuery().use { row ->
                    check(row.next()) { "Missing toolchain installation ownership." }; row.getString(1) to row.getString(2)
                }
            }
            val now = Instant.now().toString()
            connection.prepareStatement("INSERT OR IGNORE INTO storage_reservations(reservation_id,principal_id,area,purpose,resource_kind,resource_id,requested_bytes,state,created_operation_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                statement.setString(1, installationId); statement.setString(2, owner.second); statement.setString(3, "RUNNER_TOOLCHAIN")
                statement.setString(4, "TOOLCHAIN_INSTALL"); statement.setString(5, "TOOLCHAIN_INSTALLATION"); statement.setString(6, installationId)
                statement.setLong(7, requested); statement.setString(8, "ACTIVE"); statement.setString(9, owner.first); statement.setString(10, now); statement.setString(11, now); statement.executeUpdate()
            }
        }
    }

    @Synchronized private fun updateItem(id: String, artifactId: String, state: ToolchainInstallationState, bytes: Long) = connection().use { connection ->
        connection.prepareStatement("UPDATE toolchain_installation_items SET state=?,downloaded_bytes=? WHERE installation_id=? AND artifact_id=?").use { statement ->
            statement.setString(1, state.name); statement.setLong(2, bytes); statement.setString(3, id); statement.setString(4, artifactId); statement.executeUpdate()
        }
    }
    @Synchronized private fun updateInstallation(id: String, state: ToolchainInstallationState, progress: Int) = connection().use { connection ->
        val now = Instant.now().toString()
        connection.prepareStatement("UPDATE toolchain_installations SET state=?,progress_percent=?,updated_at=? WHERE installation_id=?").use { statement ->
            statement.setString(1, state.name); statement.setInt(2, progress.coerceIn(0,99)); statement.setString(3, now); statement.setString(4, id); statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE operations SET state='APPLYING',updated_at=? WHERE operation_id=(SELECT operation_id FROM toolchain_installations WHERE installation_id=?)").use { statement -> statement.setString(1, now); statement.setString(2, id); statement.executeUpdate() }
    }

    @Synchronized private fun finish(id: String, state: ToolchainInstallationState, code: String?, message: String?) = connection().use { connection ->
        val now = Instant.now().toString(); connection.autoCommit = false
        try {
            val currentProgress = connection.prepareStatement("SELECT progress_percent FROM toolchain_installations WHERE installation_id=?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { row ->
                    if (!row.next()) throw ApiException.notFound("TOOLCHAIN_INSTALLATION_NOT_FOUND", "The requested installation does not exist.")
                    row.getInt(1)
                }
            }
            connection.prepareStatement("UPDATE toolchain_installations SET state=?,progress_percent=?,reason_code=?,reason_message=?,updated_at=? WHERE installation_id=?").use { statement ->
                statement.setString(1, state.name); statement.setInt(2, if (state == ToolchainInstallationState.INSTALLED) 100 else currentProgress)
                statement.setString(3, code); statement.setString(4, message); statement.setString(5, now); statement.setString(6, id); statement.executeUpdate()
            }
            val operationState = when (state) { ToolchainInstallationState.INSTALLED, ToolchainInstallationState.CANCELLED -> "COMPLETED"; ToolchainInstallationState.RECONCILIATION_REQUIRED -> "RECONCILIATION_REQUIRED"; else -> "REJECTED" }
            connection.prepareStatement("UPDATE operations SET state=?,reason_code=?,reason_message=?,updated_at=? WHERE operation_id=(SELECT operation_id FROM toolchain_installations WHERE installation_id=?)").use { statement ->
                statement.setString(1, operationState); statement.setString(2, code); statement.setString(3, message); statement.setString(4, now); statement.setString(5, id); statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE storage_reservations SET state='RELEASED',updated_at=? WHERE reservation_id=? AND state='ACTIVE'").use { statement -> statement.setString(1, now); statement.setString(2, id); statement.executeUpdate() }
            connection.commit()
        } catch (failure: Throwable) { connection.rollback(); throw failure }
    }

    @Synchronized private fun persistInventory(installed: InstalledToolchain) = connection().use { connection ->
        val now = Instant.now().toString()
        connection.prepareStatement("INSERT OR REPLACE INTO toolchain_inventory VALUES(?,?,?,?,?,?,?,?,?,?)").use { statement ->
            statement.setString(1, installed.artifactId); statement.setString(2, installed.component.name); statement.setString(3, installed.version)
            statement.setString(4, installed.archiveSha256); statement.setString(5, installed.contentManifestSha256); statement.setLong(6, installed.installedBytes)
            statement.setString(7, installed.relativePath); statement.setString(8, ToolchainInventoryState.VERIFIED.name); statement.setString(9, now); statement.setString(10, now); statement.executeUpdate()
        }
    }

    private fun reconcileInventory() = connection().use { connection ->
        connection.createStatement().executeQuery("SELECT artifact_id,relative_path,content_manifest_sha256 FROM toolchain_inventory").use { rows ->
            while (rows.next()) {
                val valid = installer.verifyInstalled(rows.getString(2), rows.getString(3))
                connection.prepareStatement("UPDATE toolchain_inventory SET state=?,checked_at=? WHERE artifact_id=?").use { statement ->
                    statement.setString(1, if (valid) "VERIFIED" else "RECONCILIATION_REQUIRED"); statement.setString(2, Instant.now().toString()); statement.setString(3, rows.getString(1)); statement.executeUpdate()
                }
            }
        }
    }

    private fun inventoryIds(): Set<String> = connection().use { connection -> connection.createStatement().executeQuery("SELECT artifact_id FROM toolchain_inventory WHERE state='VERIFIED'").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } } }
    private fun hasCurrentLicenseAcceptance(principalId: String, license: ToolchainLicense): Boolean = connection().use { connection ->
        connection.prepareStatement("SELECT 1 FROM toolchain_license_acceptances WHERE runner_id=? AND principal_id=? AND license_id=? AND license_text_sha256=?").use { statement ->
            statement.setString(1, runnerId); statement.setString(2, principalId); statement.setString(3, license.licenseId); statement.setString(4, license.textSha256)
            statement.executeQuery().use { row -> row.next() }
        }
    }
    private fun cancellationRequested(id: String): Boolean = connection().use { connection -> connection.prepareStatement("SELECT cancel_requested FROM toolchain_installations WHERE installation_id=?").use { statement -> statement.setString(1,id); statement.executeQuery().use { row -> row.next() && row.getInt(1) != 0 } } }
    private fun inventoryInstalledBytes(connection: Connection): Long = connection.createStatement().executeQuery("SELECT COALESCE(SUM(installed_bytes),0) FROM toolchain_inventory WHERE state='VERIFIED'").use { row -> row.next(); row.getLong(1) }
    private fun reservationBytes(artifact: ToolchainCatalogArtifact): Long = Math.addExact(artifact.archiveSizeBytes.toLong(), artifact.maximumExpandedBytes.toLong())

    private fun insertOperation(connection: Connection, operationId: String, principal: String, kind: String, key: String, requestSha: String, resultId: String, now: String) {
        requireActivePrincipal(connection, principal)
        connection.prepareStatement("INSERT INTO operations(operation_id,principal_id,operation_kind,idempotency_key,request_sha256,contract_id,contract_version,state,result_type,result_id,created_at,updated_at) VALUES(?,?,?,?,?,'toolchain-install',1,'RESERVED',?,?,?,?)").use { statement ->
            statement.setString(1, operationId); statement.setString(2, principal); statement.setString(3, kind); statement.setString(4, key); statement.setString(5, requestSha)
            statement.setString(6, if (kind == "toolchain-install") "TOOLCHAIN_INSTALLATION" else "TOOLCHAIN_REMOVAL"); statement.setString(7, resultId); statement.setString(8, now); statement.setString(9, now); statement.executeUpdate()
        }
    }
    private fun existingByIdempotency(connection: Connection, principal: String, kind: String, key: String): ExistingOperation? {
        requireActivePrincipal(connection, principal)
        return connection.prepareStatement("SELECT operation_id,request_sha256,result_id FROM operations WHERE principal_id=? AND operation_kind=? AND idempotency_key=?").use { statement ->
        statement.setString(1,principal); statement.setString(2,kind); statement.setString(3,key); statement.executeQuery().use { row -> if (row.next()) ExistingOperation(row.getString(1),row.getString(2),row.getString(3)) else null }
        }
    }
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath").apply { createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") } }
    private fun canonicalSha(value: String): String = sha256(JsonCanonicalizer(value).encodedUTF8)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
    private fun uuid(): String = UUID.randomUUID().toString()
    private fun requireIdempotencyKey(value: String) { if (!IDEMPOTENCY_KEY.matches(value)) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 8..128 safe ASCII characters.") }
    override fun close() { worker.shutdownNow() }

    @kotlinx.serialization.Serializable private data class PlanDigestInput(val catalogSha256: String, val platform: String, val requirements: List<ToolchainRequirement>, val artifactIds: List<String>)
    private data class ExistingOperation(val operationId: String, val requestSha256: String, val resourceId: String)
    companion object {
        private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9._:-]{8,128}")
        private val TERMINAL_STATES = setOf(ToolchainInstallationState.INSTALLED, ToolchainInstallationState.CANCELLED, ToolchainInstallationState.FAILED, ToolchainInstallationState.RECONCILIATION_REQUIRED)
    }
}
