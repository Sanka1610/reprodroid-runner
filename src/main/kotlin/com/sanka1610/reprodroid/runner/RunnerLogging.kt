package com.sanka1610.reprodroid.runner

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Selects and prepares the state-local operational log before SLF4J is first
 * used.  The actual rotation policy lives in logback.xml so that the console
 * and file appenders remain ordinary Logback appenders.
 */
internal object RunnerLogging {
    const val OPERATIONAL_LOG_FILE = "runner.log"
    private const val STATE_DIRECTORY_PROPERTY = "reprodroid.runner.stateDirectory"
    private val privateFile = PosixFilePermissions.fromString("rw-------")

    /** Select the path used by the Logback configuration without touching disk. */
    fun select(stateDirectory: Path) {
        System.setProperty(STATE_DIRECTORY_PROPERTY, absoluteStateDirectory(stateDirectory).toString())
    }

    /**
     * Create the active file before Logback opens it and keep the file
     * owner-only on POSIX filesystems.  Existing symlinks and non-regular files
     * are rejected rather than followed.
     */
    fun prepare(stateDirectory: Path) {
        val root = absoluteStateDirectory(stateDirectory)
        check(Files.isDirectory(root, NOFOLLOW_LINKS)) { "RUNNER_LOG_STATE_INVALID" }
        val path = root.resolve(OPERATIONAL_LOG_FILE)
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "RUNNER_LOG_FILE_INVALID" }
        } else {
            val attributes = if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                arrayOf(PosixFilePermissions.asFileAttribute(privateFile))
            } else {
                emptyArray()
            }
            Files.createFile(path, *attributes)
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            check(Files.getOwner(path, NOFOLLOW_LINKS) == Files.getOwner(root, NOFOLLOW_LINKS)) {
                "RUNNER_LOG_OWNER_INVALID"
            }
            Files.setPosixFilePermissions(path, privateFile)
        }
        select(root)
    }

    fun operationalLogPath(stateDirectory: Path): Path =
        absoluteStateDirectory(stateDirectory).resolve(OPERATIONAL_LOG_FILE)

    private fun absoluteStateDirectory(stateDirectory: Path): Path =
        stateDirectory.toAbsolutePath().normalize()
}
