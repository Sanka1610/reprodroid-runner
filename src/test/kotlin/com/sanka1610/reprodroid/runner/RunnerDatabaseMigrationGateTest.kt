package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/** Real child JVM termination exercises the same SQLiteJobStore migration entry point as production. */
class RunnerDatabaseMigrationGateTest {
    @TempDir lateinit var directory: Path

    @TestFactory
    fun processDeathAtEverySnapshotCutPointPreservesSchema11ThenResumesSchema12(): List<DynamicTest> =
        RunnerMigrationCutPoint.entries.map { point -> DynamicTest.dynamicTest(point.name) {
            val state = Files.createDirectory(directory.resolve(point.name))
            fixture(state)
            val runnerId = scalar(database(state), "SELECT runner_id FROM runner_identity")
            val child = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", childClasspath(), RunnerMigrationCrashProbe::class.java.name, state.toString(), point.name,
            ).redirectErrorStream(true).start()
            try {
                assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Migration child must reach its bounded cut point")
                val output = child.inputStream.readNBytes(16 * 1024).toString(Charsets.UTF_8)
                assertEquals(46, child.exitValue(), "Migration child output: $output")
            } finally {
                if (child.isAlive) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
            }
            assertEquals(point.name, Files.readString(state.resolve("phase46-reached-cut-point")))
            assertLegacyDatabase(database(state), runnerId)
            val snapshotsDirectory = state.resolve("migration-snapshots")
            val entries = if (Files.isDirectory(snapshotsDirectory)) Files.list(snapshotsDirectory).use { it.toList() } else emptyList()
            val snapshots = entries.filter { it.fileName.toString().endsWith(".sqlite3") }
            val partials = entries.filter { it.fileName.toString().endsWith(".part") }
            when (point) {
                RunnerMigrationCutPoint.AFTER_CHECKPOINT -> { assertTrue(snapshots.isEmpty()); assertTrue(partials.isEmpty()) }
                RunnerMigrationCutPoint.AFTER_TEMPORARY_VALIDATED -> { assertTrue(snapshots.isEmpty()); assertEquals(1, partials.size) }
                RunnerMigrationCutPoint.AFTER_SNAPSHOT_MOVED -> {
                    assertEquals(1, snapshots.size)
                    assertFalse(Files.exists(snapshots.single().resolveSibling("${snapshots.single().fileName}.sha256")))
                }
                RunnerMigrationCutPoint.AFTER_SNAPSHOT_COMMITTED -> {
                    assertEquals(1, snapshots.size)
                    assertTrue(Files.isRegularFile(snapshots.single().resolveSibling("${snapshots.single().fileName}.sha256")))
                }
            }

            SQLiteJobStore(state)

            assertEquals("12", scalar(database(state), "PRAGMA user_version"))
            assertEquals(runnerId, scalar(database(state), "SELECT runner_id FROM runner_identity"))
            assertEquals("SUCCEEDED:local-development", scalar(database(state), "SELECT state || ':' || principal_id FROM jobs WHERE job_id='legacy-job'"))
            val snapshot = Files.list(snapshotsDirectory).use { paths -> paths.filter { it.fileName.toString().endsWith(".sqlite3") }.toList().single() }
            assertLegacyDatabase(snapshot, runnerId)
            val checksum = Files.readString(snapshot.resolveSibling("${snapshot.fileName}.sha256"))
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(snapshot)).joinToString("") { "%02x".format(it) }
            assertTrue(checksum.startsWith(digest))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(snapshotsDirectory))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(snapshot))
        } }

    @Test
    fun insufficientSnapshotCapacityLeavesOriginalSchemaIdentityAndRowsIntact() {
        fixture(directory)
        val runnerId = scalar(database(directory), "SELECT runner_id FROM runner_identity")
        assertThrows(IllegalStateException::class.java) {
            SQLiteJobStore(directory, migrationPolicy = RunnerMigrationPolicy(usableSpace = { 0L }))
        }
        assertLegacyDatabase(database(directory), runnerId)
        assertEquals("0", scalar(database(directory), "SELECT count(*) FROM sqlite_master WHERE name='credentials'"))
    }

    @Test
    fun concurrentWriterCannotChangeTheSnapshotSourceBeforeMigrationCommit() {
        fixture(directory)
        var attempted = false
        SQLiteJobStore(directory, migrationPolicy = RunnerMigrationPolicy(onCutPoint = { point ->
            if (point == RunnerMigrationCutPoint.AFTER_TEMPORARY_VALIDATED) {
                attempted = true
                DriverManager.getConnection("jdbc:sqlite:${database(directory)}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA busy_timeout=10")
                        assertThrows(SQLException::class.java) {
                            statement.executeUpdate("UPDATE jobs SET state='FAILED' WHERE job_id='legacy-job'")
                        }
                    }
                }
            }
        }))
        assertTrue(attempted)
        assertEquals("SUCCEEDED", scalar(database(directory), "SELECT state FROM jobs WHERE job_id='legacy-job'"))
    }

    @Test
    fun corruptPublishedSnapshotIsNotSilentlyReplacedOnRetry() {
        fixture(directory)
        val runnerId = scalar(database(directory), "SELECT runner_id FROM runner_identity")
        assertThrows(IllegalStateException::class.java) {
            SQLiteJobStore(directory, migrationPolicy = RunnerMigrationPolicy(onCutPoint = { point ->
                if (point == RunnerMigrationCutPoint.AFTER_SNAPSHOT_COMMITTED) error("Injected migration stop before DDL")
            }))
        }
        val snapshot = Files.list(directory.resolve("migration-snapshots")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".sqlite3") }.toList().single()
        }
        Files.write(snapshot, byteArrayOf(42), StandardOpenOption.APPEND)
        val corruptBytes = Files.readAllBytes(snapshot)
        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(directory) }
        assertLegacyDatabase(database(directory), runnerId)
        assertArrayEquals(corruptBytes, Files.readAllBytes(snapshot))
    }

    @Test
    fun invalidChecksumRefusesMigrationWithoutOverwritingExistingEvidence() {
        fixture(directory)
        val runnerId = scalar(database(directory), "SELECT runner_id FROM runner_identity")
        assertThrows(IllegalStateException::class.java) {
            SQLiteJobStore(directory, migrationPolicy = RunnerMigrationPolicy(onCutPoint = { point ->
                if (point == RunnerMigrationCutPoint.AFTER_SNAPSHOT_COMMITTED) error("Injected stop before DDL")
            }))
        }
        val checksum = Files.list(directory.resolve("migration-snapshots")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".sha256") }.toList().single()
        }
        val invalid = "0".repeat(64) + Files.readString(checksum).substring(64)
        Files.writeString(checksum, invalid)
        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(directory) }
        assertLegacyDatabase(database(directory), runnerId)
        assertEquals(invalid, Files.readString(checksum))
    }

    @Test
    fun corruptSourceRemainsUntouchedAndNoSnapshotIsPublished() {
        fixture(directory)
        FileChannel.open(database(directory), StandardOpenOption.WRITE).use { it.truncate(Files.size(database(directory)) / 2) }
        val bytes = Files.readAllBytes(database(directory))
        assertThrows(Exception::class.java) { SQLiteJobStore(directory) }
        assertArrayEquals(bytes, Files.readAllBytes(database(directory)))
        assertFalse(Files.exists(directory.resolve("migration-snapshots")))
    }

    @Test
    fun databaseSymlinkAndSnapshotDirectorySymlinkCannotEscapeTheTaskState() {
        fixture(directory)
        val source = database(directory)
        val retained = directory.resolve("retained-source.sqlite3")
        Files.move(source, retained)
        val before = Files.readAllBytes(retained)
        Files.createSymbolicLink(source, retained)
        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(directory) }
        assertTrue(Files.isSymbolicLink(source))
        assertArrayEquals(before, Files.readAllBytes(retained))
        Files.delete(source)
        Files.move(retained, source)
        val external = Files.createDirectory(directory.resolve("other-owned-test-data"))
        Files.writeString(external.resolve("sentinel"), "preserve")
        Files.createSymbolicLink(directory.resolve("migration-snapshots"), external)
        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(directory) }
        assertEquals("preserve", Files.readString(external.resolve("sentinel")))
        assertEquals(1L, Files.list(external).use { it.count() })
    }

    @Test
    fun nonEmptySchemaZeroIsNotMistakenForFreshInstallation() {
        DriverManager.getConnection("jdbc:sqlite:${database(directory)}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE retained_user_data(value TEXT)")
                statement.executeUpdate("INSERT INTO retained_user_data VALUES('retain schema-zero data')")
            }
        }
        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(directory) }
        assertEquals("0", scalar(database(directory), "PRAGMA user_version"))
        assertEquals("retain schema-zero data", scalar(database(directory), "SELECT value FROM retained_user_data"))
        assertEquals("0", scalar(database(directory), "SELECT count(*) FROM sqlite_master WHERE name='jobs'"))
    }

    @Test
    fun worldWritableNonStickyAncestorPreventsMigrationBeforeAnySchemaChange() {
        val parent = Files.createDirectory(directory.resolve("unsafe-parent"))
        val state = Files.createDirectory(parent.resolve("owned-state"))
        fixture(state)
        val runnerId = scalar(database(state), "SELECT runner_id FROM runner_identity")
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwxrwx"))
        try {
            assertThrows(IllegalStateException::class.java) { SQLiteJobStore(state) }
            assertLegacyDatabase(database(state), runnerId)
        } finally {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    private fun fixture(state: Path) = SQLite12MigrationTest().also { it.stateDirectory = state }.createSchema11Fixture()
    private fun database(state: Path) = state.resolve("reprodroid-runner.sqlite3")
    private fun assertLegacyDatabase(path: Path, runnerId: String) {
        assertEquals("11", scalar(path, "PRAGMA user_version"))
        assertEquals("ok", scalar(path, "PRAGMA integrity_check"))
        assertEquals(runnerId, scalar(path, "SELECT runner_id FROM runner_identity"))
        assertEquals("SUCCEEDED:REAL_TRUSTED", scalar(path, "SELECT state || ':' || execution_mode FROM jobs WHERE job_id='legacy-job'"))
        assertEquals("0", scalar(path, "SELECT count(*) FROM pragma_table_info('jobs') WHERE name='principal_id'"))
    }
    private fun scalar(path: Path, sql: String) = DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) } }
    }
    private fun childClasspath(): String = buildSet {
        addAll(System.getProperty("java.class.path").split(File.pathSeparator))
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) addAll(loader.getURLs().filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() })
            loader = loader.parent
        }
        listOf(javaClass, SQLiteJobStore::class.java, kotlin.Unit::class.java, org.sqlite.JDBC::class.java,
            org.slf4j.LoggerFactory::class.java).forEach { type -> add(Path.of(type.protectionDomain.codeSource.location.toURI()).toString()) }
    }.joinToString(File.pathSeparator)
}

/** Child-only entry point: halt bypasses finally and simulates actual process death, not an exception. */
object RunnerMigrationCrashProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val state = Path.of(args[0])
        val requested = RunnerMigrationCutPoint.valueOf(args[1])
        SQLiteJobStore(state, migrationPolicy = RunnerMigrationPolicy(onCutPoint = { point ->
            if (point == requested) {
                val marker = state.resolve("phase46-reached-cut-point")
                Files.writeString(marker, point.name, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                FileChannel.open(marker, StandardOpenOption.WRITE).use { it.force(true) }
                Runtime.getRuntime().halt(46)
            }
        }))
        error("Requested migration cut point was not reached")
    }
}
