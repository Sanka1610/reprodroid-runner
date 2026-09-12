package com.sanka1610.reprodroid.runner

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SQLite8To12MigrationTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `phase 3 sqlite8 state migrates once to sqlite12 without losing active work or ownership`() {
        createSchema8Fixture()

        val first = SQLiteJobStore(stateDirectory)
        val migrated = requireNotNull(first.getStoredJob(ACTIVE_JOB_ID))
        assertEquals(JobState.BUILDING, migrated.state)
        assertEquals(SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL, migrated.principalId)
        assertEquals(12, scalar("PRAGMA user_version").toInt())
        assertEquals("phase3-owner", scalar("SELECT owner_id FROM sandbox_owner WHERE singleton=1"))
        assertEquals("0", scalar("SELECT removed FROM sandbox_resources WHERE attempt_id='$ATTEMPT_ID'"))
        assertEquals("BUILDING", scalar("SELECT state FROM jobs WHERE job_id='$ACTIVE_JOB_ID'"))
        assertEquals("1", scalar("SELECT count(*) FROM principals WHERE principal_id='local-development' AND state='ACTIVE'"))
        assertEquals("0", scalar("SELECT count(*) FROM credentials"))
        assertEquals("0", scalar("SELECT count(*) FROM revocation_events"))
        assertEquals("ok", scalar("PRAGMA integrity_check"))
        val snapshots = Files.list(stateDirectory.resolve("migration-snapshots")).use { paths ->
            paths.map { it.fileName.toString() }.toList()
        }
        assertEquals(1, snapshots.count { it.matches(Regex("schema-8-[0-9a-f]{64}\\.sqlite3")) })
        assertEquals(1, snapshots.count { it.matches(Regex("schema-8-[0-9a-f]{64}\\.sqlite3\\.sha256")) })

        val runnerId = scalar("SELECT runner_id FROM runner_identity WHERE singleton=1")
        assertTrue(runCatching { java.util.UUID.fromString(runnerId).toString() == runnerId }.getOrDefault(false))

        SQLiteJobStore(stateDirectory)
        assertEquals(runnerId, scalar("SELECT runner_id FROM runner_identity WHERE singleton=1"))
        assertEquals("1", scalar("SELECT count(*) FROM principals WHERE principal_id='local-development'"))
        assertEquals("1", scalar("SELECT count(*) FROM sandbox_resources WHERE attempt_id='$ATTEMPT_ID'"))
        assertEquals("BUILDING", scalar("SELECT state FROM jobs WHERE job_id='$ACTIVE_JOB_ID'"))
        assertEquals("ok", scalar("PRAGMA integrity_check"))
    }

    private fun createSchema8Fixture() {
        val database = stateDirectory.resolve("reprodroid-runner.sqlite3")
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE jobs (
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
                        updated_at TEXT NOT NULL,
                        sandbox_mode TEXT,
                        sandbox_origin TEXT,
                        sandbox_profile_id TEXT,
                        sandbox_snapshot TEXT,
                        sandbox_snapshot_sha256 TEXT,
                        sandbox_cleanup_status TEXT,
                        manifest_format TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE sandbox_owner (
                        singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                        owner_id TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE sandbox_resources (
                        attempt_id TEXT PRIMARY KEY,
                        job_id TEXT NOT NULL REFERENCES jobs(job_id),
                        owner_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        expected_name TEXT NOT NULL UNIQUE,
                        engine_id TEXT NOT NULL,
                        endpoint TEXT NOT NULL,
                        container_id TEXT,
                        removed INTEGER NOT NULL DEFAULT 0,
                        observation_json TEXT,
                        build_failure_code TEXT,
                        cleanup_failure_code TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX jobs_state_index ON jobs(state)")
                statement.execute(
                    """
                    INSERT INTO jobs (
                        job_id,execution_mode,repository_url,revision_type,revision_value,
                        simulation_outcome,state,progress_percent,requires_confirmation,
                        created_at,updated_at
                    ) VALUES (
                        '$ACTIVE_JOB_ID','SIMULATED','https://github.com/example/phase3-fixture',
                        'TAG','v1','SUCCESS','BUILDING',65,0,
                        '2026-08-31T00:00:00Z','2026-08-31T00:01:00Z'
                    )
                    """.trimIndent(),
                )
                statement.execute("INSERT INTO sandbox_owner VALUES(1,'phase3-owner')")
                statement.execute(
                    """
                    INSERT INTO sandbox_resources (
                        attempt_id,job_id,owner_id,role,expected_name,engine_id,endpoint,container_id,removed
                    ) VALUES (
                        '$ATTEMPT_ID','$ACTIVE_JOB_ID','phase3-owner','BUILD',
                        'reprodroid-phase3-fixture','phase3-engine','unix:///var/run/docker.sock',
                        '${"a".repeat(64)}',0
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = 8")
            }
        }
        assertNotEquals("12", scalar("PRAGMA user_version"))
    }

    private fun scalar(sql: String): String = DriverManager.getConnection(
        "jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}",
    ).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }

    private companion object {
        const val ACTIVE_JOB_ID = "00000000-0000-4000-8000-000000000008"
        const val ATTEMPT_ID = "00000000-0000-4000-8000-000000000108"
    }
}
