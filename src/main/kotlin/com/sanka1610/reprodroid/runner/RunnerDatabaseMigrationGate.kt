package com.sanka1610.reprodroid.runner

import org.sqlite.SQLiteConfig
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

enum class RunnerMigrationCutPoint {
    AFTER_CHECKPOINT,
    AFTER_TEMPORARY_VALIDATED,
    AFTER_SNAPSHOT_MOVED,
    AFTER_SNAPSHOT_COMMITTED,
}

/** Fault-injection and capacity policy; production never deletes data to manufacture capacity. */
data class RunnerMigrationPolicy(
    val recoveryReserveBytes: Long = 64L * 1024 * 1024,
    val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    val onCutPoint: (RunnerMigrationCutPoint) -> Unit = {},
)

/**
 * Retains a verified pre-migration database before any schema DDL.
 * The same EXCLUSIVE SQLite transaction spans the snapshot and migration: an
 * older process that does not understand our file lock still cannot write in between.
 */
internal object RunnerDatabaseMigrationGate {
    private val privateDirectory = PosixFilePermissions.fromString("rwx------")
    private val privateFile = PosixFilePermissions.fromString("rw-------")

    @Synchronized
    fun initialize(
        stateDirectory: Path,
        targetVersion: Int,
        policy: RunnerMigrationPolicy = RunnerMigrationPolicy(),
        migrate: (Connection) -> Unit,
    ) {
        require(targetVersion > 0 && policy.recoveryReserveBytes >= 0)
        val root = stateDirectory.toAbsolutePath().normalize()
        prepareStateDirectory(root)
        val database = root.resolve("reprodroid-runner.sqlite3")
        if (Files.exists(database, NOFOLLOW_LINKS)) validateRegular(database, root)
        val lock = root.resolve("database-migration.lock")
        openPrivate(lock, setOf(CREATE, READ, WRITE), root).use { channel ->
            val lease = channel.tryLock() ?: error("MIGRATION_DATABASE_BUSY: another initializer is running.")
            lease.use {
                val configuration = SQLiteConfig().apply {
                    setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE)
                    setBusyTimeout(5_000)
                    enforceForeignKeys(true)
                }
                DriverManager.getConnection("jdbc:sqlite:$database", configuration.toProperties()).use { connection ->
                    val version = version(connection)
                    require(version in 0..targetVersion) { "Unsupported Runner database schema version: $version" }
                    if (version == 0) connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").use { rows ->
                            check(rows.next() && rows.getInt(1) == 0) { "MIGRATION_UNVERSIONED_DATABASE: existing data is not a fresh database." }
                        }
                    }
                    if (version in 1 until targetVersion) {
                        connection.createStatement().use { statement ->
                            statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { result ->
                                check(result.next() && result.getInt(1) == 0 && result.getInt(2) == result.getInt(3)) {
                                    "MIGRATION_DATABASE_BUSY: a complete checkpoint is required."
                                }
                            }
                        }
                    }
                    // SQLite JDBC starts BEGIN EXCLUSIVE when autoCommit becomes false.
                    connection.autoCommit = false
                    try {
                        check(version(connection) == version) { "MIGRATION_DATABASE_CHANGED: retry after other processes stop." }
                        if (version in 1 until targetVersion) {
                            val wal = root.resolve("reprodroid-runner.sqlite3-wal")
                            check(!Files.exists(wal, NOFOLLOW_LINKS) ||
                                (Files.isRegularFile(wal, NOFOLLOW_LINKS) && Files.size(wal) == 0L)) {
                                "MIGRATION_DATABASE_BUSY: WAL changed after checkpoint."
                            }
                            val identity = identity(connection, version)
                            verifyIntegrity(connection)
                            policy.onCutPoint(RunnerMigrationCutPoint.AFTER_CHECKPOINT)
                            snapshot(database, root, version, identity, policy)
                        }
                        migrate(connection)
                        check(version(connection) == targetVersion) { "MIGRATION_SCHEMA_NOT_COMMITTED" }
                    } catch (failure: Throwable) {
                        runCatching { connection.rollback() }
                        throw failure
                    }
                }
            }
        }
    }

    /**
     * Establish the state root before any dependency can initialize logging and
     * create it using the process umask. New POSIX roots are owner-only, while
     * existing roots keep the migration gate's non-writable ancestor checks.
     */
    fun prepareStateDirectory(stateDirectory: Path) {
        val root = stateDirectory.toAbsolutePath().normalize()
        createState(root)
        validateState(root)
    }

    private fun snapshot(database: Path, root: Path, version: Int, identity: String?, policy: RunnerMigrationPolicy) {
        val directory = root.resolve("migration-snapshots")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, *directoryAttributes(root))
            fsync(root)
        }
        validateDirectory(directory, root, private = true)
        val bytes = Files.size(database)
        val required = Math.addExact(Math.multiplyExact(bytes, 2L), policy.recoveryReserveBytes)
        check(bytes > 0 && policy.usableSpace(root) >= required) {
            "MIGRATION_CAPACITY_UNAVAILABLE: verified snapshot and recovery reserve are required."
        }
        val sourceSha256 = digest(database)
        val target = directory.resolve("schema-$version-$sourceSha256.sqlite3")
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            validateSnapshot(target, root, bytes, sourceSha256, version, identity)
        } else {
            val temporary = directory.resolve(".schema-$version-${UUID.randomUUID()}.part")
            // A unique, pre-created 0600 file prevents a partial snapshot from ever becoming public.
            openPrivate(temporary, setOf(CREATE_NEW, WRITE), root).use { output ->
                FileChannel.open(database, READ, NOFOLLOW_LINKS).use { input ->
                    var copied = 0L
                    while (copied < bytes) {
                        val count = input.transferTo(copied, minOf(bytes - copied, 64L * 1024 * 1024), output)
                        check(count > 0) { "MIGRATION_SNAPSHOT_INCOMPLETE" }
                        copied += count
                    }
                    check(input.size() == bytes) { "MIGRATION_DATABASE_CHANGED" }
                }
                output.force(true)
            }
            validateSnapshot(temporary, root, bytes, sourceSha256, version, identity)
            check(digest(database) == sourceSha256) { "MIGRATION_DATABASE_CHANGED" }
            policy.onCutPoint(RunnerMigrationCutPoint.AFTER_TEMPORARY_VALIDATED)
            Files.move(temporary, target, ATOMIC_MOVE)
            fsync(directory)
            policy.onCutPoint(RunnerMigrationCutPoint.AFTER_SNAPSHOT_MOVED)
        }
        val checksum = directory.resolve("${target.fileName}.sha256")
        val expected = "$sourceSha256  ${target.fileName}\n".toByteArray(Charsets.US_ASCII)
        if (Files.exists(checksum, NOFOLLOW_LINKS)) {
            validateRegular(checksum, root, private = true)
            check(Files.size(checksum) == expected.size.toLong()) { "MIGRATION_SNAPSHOT_CHECKSUM_INVALID" }
            FileChannel.open(checksum, READ, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteBuffer.allocate(expected.size)
                while (buffer.hasRemaining()) check(input.read(buffer) > 0) { "MIGRATION_SNAPSHOT_CHECKSUM_INVALID" }
                check(MessageDigest.isEqual(expected, buffer.array())) { "MIGRATION_SNAPSHOT_CHECKSUM_INVALID" }
            }
        } else {
            val staging = directory.resolve(".checksum-${UUID.randomUUID()}.part")
            openPrivate(staging, setOf(CREATE_NEW, WRITE), root).use { output ->
                val buffer = ByteBuffer.wrap(expected)
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(staging, checksum, ATOMIC_MOVE)
            fsync(directory)
        }
        policy.onCutPoint(RunnerMigrationCutPoint.AFTER_SNAPSHOT_COMMITTED)
    }

    private fun validateSnapshot(path: Path, root: Path, bytes: Long, hash: String, version: Int, identity: String?) {
        validateRegular(path, root, private = true)
        check(Files.size(path) == bytes && digest(path) == hash) { "MIGRATION_SNAPSHOT_INVALID" }
        // Immutable read mode neither creates WAL/SHM sidecars nor changes the retained snapshot.
        DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro&immutable=1").use { connection ->
            check(version(connection) == version && identity(connection, version) == identity) { "MIGRATION_SNAPSHOT_IDENTITY_INVALID" }
            verifyIntegrity(connection)
        }
        check(digest(path) == hash) { "MIGRATION_SNAPSHOT_CHANGED" }
    }

    private fun version(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { rows -> check(rows.next()); rows.getInt(1) }
    }

    private fun identity(connection: Connection, version: Int): String? {
        if (version < 9) return null
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT singleton,runner_id FROM runner_identity").use { rows ->
                check(rows.next()) { "RUNNER_IDENTITY_MISSING" }
                val id = rows.getString(2)
                check(rows.getInt(1) == 1 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && !rows.next()) {
                    "RUNNER_IDENTITY_INVALID"
                }
                id
            }
        }
    }

    private fun verifyIntegrity(connection: Connection) = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA integrity_check").use { rows ->
            check(rows.next() && rows.getString(1) == "ok" && !rows.next()) { "MIGRATION_DATABASE_CORRUPT" }
        }
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                buffer.flip(); digest.update(buffer); buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createState(root: Path) {
        val missing = mutableListOf<Path>()
        var path: Path? = root
        while (path != null) {
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                validateAncestor(path, root)
            } else missing.add(path)
            path = path.parent
        }
        missing.asReversed().forEach { directory ->
            Files.createDirectory(directory, *directoryAttributes(directory.parent))
            fsync(directory.parent)
        }
    }

    private fun validateState(root: Path) {
        var path: Path? = root
        while (path != null) {
            validateAncestor(path, root)
            path = path.parent
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            val user = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            check(Files.getOwner(root, NOFOLLOW_LINKS) == user) { "MIGRATION_OWNER_INVALID: state must be owned by the current user." }
        }
        validateDirectory(root, root, private = false)
    }

    private fun validateAncestor(path: Path, root: Path) {
        check(!Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW_LINKS)) { "MIGRATION_STATE_PATH_INVALID" }
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) return
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        val writable = permissions.any {
            it == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE || it == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
        }
        val sticky = (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int) and 0x200 != 0
        check(!writable || (sticky && path != root)) { "MIGRATION_PERMISSIONS_INVALID: writable ancestor is not private." }
    }

    private fun validateDirectory(path: Path, root: Path, private: Boolean) {
        check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "MIGRATION_DIRECTORY_INVALID" }
        validatePermissions(path, root, if (private) privateDirectory else null)
    }

    private fun validateRegular(path: Path, root: Path, private: Boolean = false) {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "MIGRATION_FILE_INVALID" }
        validatePermissions(path, root, if (private) privateFile else null)
    }

    private fun validatePermissions(path: Path, root: Path, exact: Set<java.nio.file.attribute.PosixFilePermission>?) {
        if (!Files.getFileStore(root).supportsFileAttributeView("posix")) return
        check(Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(root, NOFOLLOW_LINKS)) { "MIGRATION_OWNER_INVALID" }
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        check(if (exact != null) permissions == exact else permissions.none {
            it == java.nio.file.attribute.PosixFilePermission.GROUP_WRITE || it == java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
        }) { "MIGRATION_PERMISSIONS_INVALID" }
    }

    private fun openPrivate(path: Path, options: Set<java.nio.file.OpenOption>, root: Path): FileChannel {
        if (Files.exists(path, NOFOLLOW_LINKS)) validateRegular(path, root, private = true)
        val attributes = if (Files.getFileStore(root).supportsFileAttributeView("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(privateFile)) else emptyArray()
        return FileChannel.open(path, options + NOFOLLOW_LINKS, *attributes).also {
            try { validateRegular(path, root, private = true) } catch (failure: Throwable) { it.close(); throw failure }
        }
    }

    private fun directoryAttributes(root: Path) = if (Files.getFileStore(root).supportsFileAttributeView("posix"))
        arrayOf(PosixFilePermissions.asFileAttribute(privateDirectory)) else emptyArray()

    private fun fsync(directory: Path) { FileChannel.open(directory, READ).use { it.force(true) } }
}
