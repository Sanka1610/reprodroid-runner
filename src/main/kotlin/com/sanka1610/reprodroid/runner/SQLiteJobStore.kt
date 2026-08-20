package com.sanka1610.reprodroid.runner

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories

internal data class StoredJob(
    val jobId: String,
    val executionMode: ExecutionMode,
    val repositoryUrl: String,
    val revision: RequestedRevision,
    val simulationOutcome: SimulationOutcome?,
    val resolvedCommitSha: String?,
    val state: JobState,
)

internal data class StoredArtifact(
    val metadata: ArtifactMetadata,
    val contentRelativePath: String?,
)

internal enum class CancelJobResult {
    CANCELLED,
    NOT_FOUND,
    ALREADY_TERMINAL,
}

class SQLiteJobStore(
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
    private val logDirectory = stateDirectory.resolve("logs")

    init {
        stateDirectory.createDirectories()
        logDirectory.createDirectories()
        Class.forName("org.sqlite.JDBC")
        initializeSchema()
    }

    @Synchronized
    fun createJob(request: CreateJobRequest): CreateJobResponse {
        val jobId = UUID.randomUUID().toString()
        val now = Instant.now(clock).toString()
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO jobs (
                    job_id, execution_mode, repository_url, revision_type, revision_value,
                    simulation_outcome, state, progress_percent, requires_confirmation,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jobId)
                statement.setString(2, request.executionMode.name)
                statement.setString(3, request.repositoryUrl)
                statement.setString(4, request.revision.type.name)
                statement.setString(5, request.revision.value)
                statement.setString(6, request.simulationOutcome?.name)
                statement.setString(7, JobState.CREATED.name)
                statement.setString(8, now)
                statement.setString(9, now)
                statement.executeUpdate()
            }
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

    @Synchronized
    internal fun getStoredJob(jobId: String): StoredJob? = connection().use { connection ->
        connection.prepareStatement(
            """
            SELECT job_id, execution_mode, repository_url, revision_type, revision_value,
                   simulation_outcome, resolved_commit_sha, state
            FROM jobs WHERE job_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                result.toStoredJob()
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
                )
            }
        }
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
                SET state = ?, progress_percent = 5, effective_build_root = ?,
                    effective_build_tasks = ?, updated_at = ?
                WHERE job_id = ? AND state = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.RESOLVING_SOURCE.name)
                statement.setString(2, recipe.buildRoot)
                statement.setString(3, recipe.tasks.joinToString("\n"))
                statement.setString(4, Instant.now(clock).toString())
                statement.setString(5, jobId)
                statement.setString(6, JobState.CREATED.name)
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
    fun confirmRealJob(jobId: String, resolvedCommitSha: String): Boolean {
        val current = getStoredJob(jobId) ?: return false
        if (current.state != JobState.AWAITING_CONFIRMATION || current.resolvedCommitSha != resolvedCommitSha) return false
        appendLog(jobId, LogLevel.WARN, "Client acknowledged RCE risk for commit $resolvedCommitSha.")
        return connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE jobs
                SET state = ?, progress_percent = 15, requires_confirmation = 0, updated_at = ?
                WHERE job_id = ? AND state = ? AND resolved_commit_sha = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, JobState.QUEUED.name)
                statement.setString(2, Instant.now(clock).toString())
                statement.setString(3, jobId)
                statement.setString(4, JobState.AWAITING_CONFIRMATION.name)
                statement.setString(5, resolvedCommitSha)
                statement.executeUpdate() == 1
            }
        }
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
        Files.createDirectories(logPath.parent)

        connection().use { connection ->
            connection.autoCommit = false
            try {
                val sequence = latestLogSequence(connection, jobId) + 1
                val offset: Long
                RandomAccessFile(logPath.toFile(), "rw").use { file ->
                    offset = file.length()
                    file.seek(offset)
                    file.write(messageBytes)
                    file.fd.sync()
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
                RandomAccessFile(logPath.toFile(), "r").use { file ->
                    indexedEntries.map { indexed ->
                        val bytes = ByteArray(indexed.byteLength)
                        file.seek(indexed.offset)
                        file.readFully(bytes)
                        LogEntry(
                            sequence = indexed.sequence,
                            timestamp = indexed.timestamp,
                            level = indexed.level,
                            message = String(bytes, StandardCharsets.UTF_8),
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
    fun markRunningJobsInterrupted(): List<String> {
        val interruptibleStates = setOf(
            JobState.CREATED,
            JobState.RESOLVING_SOURCE,
            JobState.QUEUED,
            JobState.CLONING,
            JobState.VERIFYING_WRAPPER,
            JobState.BUILDING,
            JobState.DISCOVERING_ARTIFACTS,
        )
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
                        while (result.next()) add(result.getString("job_id"))
                    }
                }
            }
        }
        interrupted.forEach { jobId ->
            deleteArtifacts(jobId)
            appendLog(jobId, LogLevel.WARN, "Job marked INTERRUPTED during Runner startup.")
            updateState(
                jobId = jobId,
                state = JobState.INTERRUPTED,
                progressPercent = getJob(jobId)?.progressPercent ?: 0,
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

    private fun initializeSchema() {
        connection().use { connection ->
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
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS jobs (
                            job_id TEXT PRIMARY KEY,
                            execution_mode TEXT NOT NULL,
                            repository_url TEXT NOT NULL,
                            revision_type TEXT NOT NULL,
                            revision_value TEXT NOT NULL,
                            simulation_outcome TEXT,
                            resolved_commit_sha TEXT,
                            state TEXT NOT NULL,
                            progress_percent INTEGER NOT NULL,
                            requires_confirmation INTEGER NOT NULL,
                            effective_build_root TEXT,
                            effective_build_tasks TEXT,
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
                    if (schemaVersion == 1) {
                        AUDIT_COLUMNS.forEach { columnDefinition ->
                            statement.executeUpdate("ALTER TABLE jobs ADD COLUMN $columnDefinition")
                        }
                    }
                    if (schemaVersion in 1..2 && !columnExists(connection, "artifacts", "content_path")) {
                        statement.executeUpdate("ALTER TABLE artifacts ADD COLUMN content_path TEXT")
                    }
                    statement.execute("PRAGMA user_version = $SCHEMA_VERSION")
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath").apply {
        createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA busy_timeout = 5000")
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

    private fun ResultSet.toStoredJob(): StoredJob = StoredJob(
        jobId = getString("job_id"),
        executionMode = ExecutionMode.valueOf(getString("execution_mode")),
        repositoryUrl = getString("repository_url"),
        revision = RequestedRevision(
            type = RevisionType.valueOf(getString("revision_type")),
            value = getString("revision_value"),
        ),
        simulationOutcome = getString("simulation_outcome")?.let(SimulationOutcome::valueOf),
        resolvedCommitSha = getString("resolved_commit_sha"),
        state = JobState.valueOf(getString("state")),
    )

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
                buildRoot = buildRoot,
                tasks = getString("effective_build_tasks")
                    ?.split('\n')
                    ?.filter(String::isNotBlank)
                    .orEmpty(),
            )
        },
        latestLogSequence = latestLogSequence,
        artifacts = artifacts,
        error = getString("error_code")?.let { code ->
            JobError(code, getString("error_message"))
        },
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )

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

    private companion object {
        const val SCHEMA_VERSION = 3
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
    }
}
