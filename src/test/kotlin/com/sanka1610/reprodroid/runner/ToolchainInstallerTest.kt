package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolchainInstallerTest {
    @TempDir lateinit var stateDirectory: Path

    @Test
    fun `verified archive is atomically published inventoried and manually removable`() {
        val archive = stateDirectory.resolve("fixture.zip")
        zip(archive, mapOf("gradle-fixture/bin/gradle" to "#!/bin/sh\n", "gradle-fixture/lib/marker" to "ok"))
        val artifact = artifact(archive)
        val installer = installerFor(archive)
        val states = mutableListOf<ToolchainInstallationState>()

        val installed = installer.install(UUID.randomUUID().toString(), artifact, { false }) { state, _ -> states += state }

        val final = stateDirectory.resolve(installed.relativePath)
        assertTrue(final.resolve("bin/gradle").toFile().isFile)
        assertTrue(final.resolve(".reprodroid-content-manifest").toFile().isFile)
        assertTrue(states.containsAll(listOf(ToolchainInstallationState.VERIFYING_ARCHIVE, ToolchainInstallationState.EXTRACTING, ToolchainInstallationState.PUBLISHING)))
        assertTrue(installed.contentManifestSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
        final.resolve("lib/marker").toFile().setWritable(true, true)
        Files.writeString(final.resolve("lib/marker"), "tampered")
        assertFalse(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
        assertTrue(installer.remove(installed.relativePath) > 0)
        assertFalse(Files.exists(final))
    }

    @Test
    fun `zip traversal is rejected before final publish`() {
        val archive = stateDirectory.resolve("escape.zip")
        zip(archive, mapOf("../escape" to "bad", "gradle-fixture/bin/gradle" to "x", "gradle-fixture/lib/marker" to "x"))
        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(UUID.randomUUID().toString(), artifact(archive), { false }) { _, _ -> }
        }
        assertEquals("ARCHIVE_PATH_INVALID", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("escape")))
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/gradle/fixture")))
    }

    @Test
    fun `archive entry limit is enforced before final publish`() {
        val archive = stateDirectory.resolve("too-many.zip")
        zip(archive, mapOf("gradle-fixture/bin/gradle" to "x", "gradle-fixture/lib/marker" to "x"))
        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(
                UUID.randomUUID().toString(),
                artifact(archive).copy(maximumEntries = 1),
                { false },
            ) { _, _ -> }
        }
        assertEquals("ARCHIVE_ENTRY_LIMIT_EXCEEDED", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/gradle/fixture")))
    }

    private fun installerFor(source: Path) = ToolchainInstaller(
        stateDirectory,
        archiveDownloader = ToolchainArchiveDownloader { artifact, destination, cancelled, progress ->
            if (cancelled()) throw ToolchainCancelledException()
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            progress(Files.size(destination))
            assertEquals(artifact.archiveSizeBytes.toLong(), Files.size(destination))
        },
    )

    private fun artifact(archive: Path) = ToolchainCatalogArtifact(
        artifactId = "gradle-fixture",
        component = ToolchainComponent.GRADLE,
        version = "fixture",
        platform = "linux-x86_64",
        url = "https://downloads.gradle.org/fixture.zip",
        archiveType = ToolchainArchiveType.ZIP,
        archiveSha256 = sha256(archive),
        archiveSizeBytes = Files.size(archive).toString(),
        maximumExpandedBytes = "1048576",
        maximumEntries = 32,
        installSubdirectory = "gradle/fixture",
        licenseId = "gradle-apache-2.0",
    )

    private fun zip(path: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name)); output.write(value.encodeToByteArray()); output.closeEntry()
            }
        }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toLowerHex()
}
