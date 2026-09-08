package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Covers the durable part of the SQLite 11 -> 12 boundary without inventing
 * pairing behavior that is not exposed by the current Runner production API.
 */
class SQLite12MigrationTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun migrationPreservesLegacyJobsAndCreatesHashedCredentialSchema() {
        createSchema11Fixture()
        val runnerIdBefore = scalar("SELECT runner_id FROM runner_identity")

        SQLiteJobStore(stateDirectory)

        withDatabase { connection ->
            connection.createStatement().use { statement ->
                assertEquals(12, statement.executeQuery("PRAGMA user_version").use { result ->
                    assertTrue(result.next())
                    result.getInt(1)
                })
                statement.executeQuery(
                    "SELECT execution_mode, state, principal_id FROM jobs WHERE job_id = 'legacy-job'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("REAL_TRUSTED", result.getString("execution_mode"))
                    assertEquals("SUCCEEDED", result.getString("state"))
                    assertEquals("local-development", result.getString("principal_id"))
                    assertFalse(result.next())
                }

                val tables = buildSet {
                    statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'table'",
                    ).use { result ->
                        while (result.next()) add(result.getString(1))
                    }
                }
                assertTrue(tables.containsAll(EXPECTED_PHASE_46_TABLES))
                assertEquals(runnerIdBefore, scalar("SELECT runner_id FROM runner_identity"))

                val schemaSql = buildString {
                    statement.executeQuery(
                        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name IN " +
                            "('pairing_invitations','pairing_requests','credentials')",
                    ).use { result ->
                        while (result.next()) append(result.getString(1)).append('\n')
                    }
                }.lowercase()
                assertTrue(schemaSql.contains("secret_sha256"))
                assertTrue(schemaSql.contains("token_sha256"))
                assertTrue(schemaSql.contains("continuation_sha256"))
                assertFalse(schemaSql.contains("invitation_secret"))
                assertFalse(schemaSql.contains("bearer_secret"))
                assertFalse(schemaSql.contains("continuation_secret"))
            }
        }

        // Restart is part of the migration boundary: the legacy principal is
        // not duplicated and the upgraded schema remains usable.
        SQLiteJobStore(stateDirectory)
        withDatabase { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM principals WHERE principal_id = 'local-development'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun unknownFutureSchemaFailsClosedWithoutCreatingPhase46Tables() {
        val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = 13")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            SQLiteJobStore(stateDirectory)
        }

        withDatabase { connection ->
            connection.createStatement().use { statement ->
                assertEquals(13, statement.executeQuery("PRAGMA user_version").use { result ->
                    assertTrue(result.next())
                    result.getInt(1)
                })
                assertFalse(
                    statement.executeQuery(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'principals'",
                    ).use { result -> result.next() },
                )
            }
        }
    }

    @Test
    fun schemaCreationFailureRollsBackLegacyOwnershipColumnAndPreservesPriorDatabase() {
        createSchema11Fixture()
        val runnerId = scalar("SELECT runner_id FROM runner_identity")
        // A reserved-name collision forces migration to fail at the principal
        // insert after it has attempted other DDL. It must not publish schema12
        // or leave the earlier migration statements partially committed.
        withDatabase { connection -> connection.createStatement().use {
            it.executeUpdate("CREATE TABLE principals (principal_id TEXT PRIMARY KEY, fixture_sentinel TEXT NOT NULL)")
            it.executeUpdate("INSERT INTO principals VALUES('retained-fixture','must survive failed migration')")
        } }

        assertThrows(Exception::class.java) { SQLiteJobStore(stateDirectory) }

        assertEquals("11", scalar("PRAGMA user_version"))
        assertEquals(runnerId, scalar("SELECT runner_id FROM runner_identity"))
        assertEquals("SUCCEEDED", scalar("SELECT state FROM jobs WHERE job_id='legacy-job'"))
        assertEquals("must survive failed migration", scalar("SELECT fixture_sentinel FROM principals"))
        assertEquals("0", scalar("SELECT count(*) FROM pragma_table_info('jobs') WHERE name='principal_id'"))
        assertEquals("0", scalar("SELECT count(*) FROM sqlite_master WHERE name='credentials'"))
        assertEquals("ok", scalar("PRAGMA integrity_check"))
    }

    private fun scalar(sql: String): String = DriverManager.getConnection(
        "jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}",
    ).use { connection -> connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) }
    } }

    internal fun createSchema11Fixture() {
        // Build the pre-4.6 tables through the current schema creator first,
        // then remove only the 4.6 additions.  This keeps the fixture aligned
        // with the real schema-11 column definitions instead of reducing it to
        // a hand-written jobs-only approximation.
        SQLiteJobStore(stateDirectory)
        withDatabase { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = OFF")
                statement.execute("DROP TABLE ownership_adoption_audit")
                statement.execute("DROP TABLE ownership_adoption_previews")
                statement.execute("DROP TABLE security_audit_events")
                statement.execute("DROP TABLE revocation_events")
                statement.execute("DROP TABLE credentials")
                statement.execute("DROP TABLE pairing_requests")
                statement.execute("DROP TABLE pairing_invitations")
                statement.execute("DROP TABLE security_certificates")
                statement.execute("DROP TABLE principals")
                statement.execute("DROP INDEX jobs_principal_index")
                statement.execute("ALTER TABLE jobs DROP COLUMN principal_id")
                statement.execute("PRAGMA user_version = 11")
            }
        }
        withDatabase { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO jobs (
                        job_id, execution_mode, repository_url, revision_type, revision_value,
                        state, progress_percent, requires_confirmation, created_at, updated_at
                    ) VALUES (
                        'legacy-job', 'REAL_TRUSTED', 'https://github.com/example/app',
                        'TAG', 'v1', 'SUCCEEDED', 100, 0,
                        '2026-09-07T00:00:00Z', '2026-09-07T00:01:00Z'
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = 11")
            }
        }
    }

    private fun withDatabase(block: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(
            "jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}",
        ).use(block)
    }

    private companion object {
        val EXPECTED_PHASE_46_TABLES = setOf(
            "security_certificates",
            "principals",
            "pairing_invitations",
            "pairing_requests",
            "credentials",
            "revocation_events",
            "ownership_adoption_previews",
            "ownership_adoption_audit",
            "security_audit_events",
        )
    }
}
