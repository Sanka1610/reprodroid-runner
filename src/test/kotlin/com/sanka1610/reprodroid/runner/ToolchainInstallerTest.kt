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
        assertTrue(Files.isExecutable(final.resolve("bin/gradle")))
        assertTrue(final.resolve(".reprodroid-content-manifest").toFile().isFile)
        assertTrue(states.containsAll(listOf(ToolchainInstallationState.VERIFYING_ARCHIVE, ToolchainInstallationState.EXTRACTING, ToolchainInstallationState.PUBLISHING)))
        assertTrue(installed.contentManifestSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
        val executable = final.resolve("bin/gradle")
        executable.toFile().setExecutable(false, false)
        assertFalse(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
        executable.toFile().setExecutable(true, false)
        final.resolve("lib/marker").toFile().setWritable(true, true)
        Files.writeString(final.resolve("lib/marker"), "tampered")
        assertFalse(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
        assertTrue(installer.remove(installed.relativePath) > 0)
        assertFalse(Files.exists(final))
    }

    @Test
    fun `jdk process helper is executable and covered by the content manifest`() {
        val archive = stateDirectory.resolve("jdk-fixture.zip")
        zip(
            archive,
            mapOf(
                "jdk-fixture/bin/java" to "#!/bin/sh\n",
                "jdk-fixture/release" to "JAVA_VERSION=fixture\n",
                "jdk-fixture/lib/jspawnhelper" to "helper",
                "jdk-fixture/lib/marker" to "not executable",
            ),
        )
        val installer = installerFor(archive)

        val installed = installer.install(UUID.randomUUID().toString(), jdkArtifact(archive), { false }) { _, _ -> }

        val final = stateDirectory.resolve(installed.relativePath)
        val java = final.resolve("bin/java")
        val helper = final.resolve("lib/jspawnhelper")
        val marker = final.resolve("lib/marker")
        assertTrue(Files.isExecutable(java))
        assertTrue(Files.isExecutable(helper))
        assertFalse(Files.isExecutable(marker))
        assertFalse(Files.isWritable(java))
        assertFalse(Files.isWritable(helper))
        assertFalse(Files.isWritable(marker))
        assertTrue(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))

        helper.toFile().setExecutable(false, false)

        assertFalse(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
    }

    @Test
    fun `jdk missing process helper is rejected before final publish`() {
        val archive = stateDirectory.resolve("jdk-missing-helper.zip")
        zip(
            archive,
            mapOf(
                "jdk-fixture/bin/java" to "#!/bin/sh\n",
                "jdk-fixture/release" to "JAVA_VERSION=fixture\n",
            ),
        )

        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(UUID.randomUUID().toString(), jdkArtifact(archive), { false }) { _, _ -> }
        }

        assertEquals("TOOLCHAIN_METADATA_INVALID", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/jdk/fixture")))
    }

    @Test
    fun `jdk process helper symlink is rejected before final publish`() {
        val payload = jdkTarPayload("jdk-helper-link")
        Files.writeString(payload.resolve("lib/helper-target"), "helper")
        Files.createSymbolicLink(payload.resolve("lib/jspawnhelper"), Path.of("helper-target"))
        val archive = stateDirectory.resolve("jdk-helper-link.tar.gz")
        tarGz(payload, archive)

        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(
                UUID.randomUUID().toString(),
                jdkArtifact(archive).copy(archiveType = ToolchainArchiveType.TAR_GZ),
                { false },
            ) { _, _ -> }
        }

        assertEquals("TOOLCHAIN_METADATA_INVALID", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/jdk/fixture")))
    }

    @Test
    fun `minor Android platform publishes catalog-bound local package metadata`() {
        val archive = stateDirectory.resolve("android-platform.zip")
        zip(
            archive,
            mapOf(
                "android.jar" to "jar",
                "source.properties" to """
                    Pkg.Revision=2
                    AndroidVersion.ApiLevel=37.0
                    AndroidVersion.CodeName=
                    AndroidVersion.ExtensionLevel=22
                    AndroidVersion.IsBaseSdk=true
                    Layoutlib.Api=15
                """.trimIndent() + "\n",
            ),
        )
        val installer = installerFor(archive)

        val installed = installer.install(UUID.randomUUID().toString(), androidPlatformArtifact(archive), { false }) { _, _ -> }

        val final = stateDirectory.resolve(installed.relativePath)
        val packageXml = Files.readString(final.resolve("package.xml"))
        assertTrue(packageXml.contains("path=\"platforms;android-37.0\""))
        assertTrue(packageXml.contains("<api-level>37.0</api-level>"))
        assertTrue(packageXml.contains("<extension-level>22</extension-level>"))
        assertTrue(packageXml.contains("<major>2</major>"))
        assertTrue(packageXml.contains("<display-name>Android SDK Platform 37.0</display-name>"))
        assertTrue(final.resolve(".reprodroid-content-manifest").toFile().readText().contains("package.xml"))
        assertTrue(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))

        final.toFile().setWritable(true, true)
        final.resolve("package.xml").toFile().setWritable(true, true)
        Files.writeString(final.resolve("package.xml"), packageXml.replace("37.0", "37.1"))
        assertFalse(installer.verifyInstalled(installed.relativePath, installed.contentManifestSha256))
    }

    @Test
    fun `minor Android platform rejects source metadata that differs from catalog`() {
        val archive = stateDirectory.resolve("android-platform-mismatch.zip")
        zip(
            archive,
            mapOf(
                "android.jar" to "jar",
                "source.properties" to """
                    Pkg.Revision=2
                    AndroidVersion.ApiLevel=37
                    AndroidVersion.CodeName=
                    AndroidVersion.ExtensionLevel=22
                    AndroidVersion.IsBaseSdk=true
                    Layoutlib.Api=15
                """.trimIndent() + "\n",
            ),
        )

        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(UUID.randomUUID().toString(), androidPlatformArtifact(archive), { false }) { _, _ -> }
        }

        assertEquals("TOOLCHAIN_METADATA_INVALID", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/android/platforms/android-37.0")))
    }

    @Test
    fun `minor Android platform rejects archive package metadata that differs from catalog`() {
        val archive = stateDirectory.resolve("android-platform-package-mismatch.zip")
        zip(
            archive,
            mapOf(
                "android.jar" to "jar",
                "source.properties" to """
                    Pkg.Revision=2
                    AndroidVersion.ApiLevel=37.0
                    AndroidVersion.CodeName=
                    AndroidVersion.ExtensionLevel=22
                    AndroidVersion.IsBaseSdk=true
                    Layoutlib.Api=15
                """.trimIndent() + "\n",
                "package.xml" to "<repository><localPackage path=\"platforms;android-37.1\"/></repository>\n",
            ),
        )

        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(UUID.randomUUID().toString(), androidPlatformArtifact(archive), { false }) { _, _ -> }
        }

        assertEquals("TOOLCHAIN_METADATA_INVALID", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/android/platforms/android-37.0")))
    }

    @Test
    fun `jdk lib directory symlink is rejected before final publish`() {
        val payload = stateDirectory.resolve("jdk-lib-link/jdk-fixture")
        Files.createDirectories(payload.resolve("bin"))
        Files.createDirectories(payload.resolve("real-lib"))
        Files.writeString(payload.resolve("bin/java"), "#!/bin/sh\n")
        Files.writeString(payload.resolve("release"), "JAVA_VERSION=fixture\n")
        Files.writeString(payload.resolve("real-lib/jspawnhelper"), "helper")
        Files.createSymbolicLink(payload.resolve("lib"), Path.of("real-lib"))
        val archive = stateDirectory.resolve("jdk-lib-link.tar.gz")
        tarGz(payload, archive)

        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(
                UUID.randomUUID().toString(),
                jdkArtifact(archive).copy(archiveType = ToolchainArchiveType.TAR_GZ),
                { false },
            ) { _, _ -> }
        }

        assertEquals("TOOLCHAIN_METADATA_INVALID", failure.code)
        assertFalse(Files.exists(stateDirectory.resolve("toolchains/jdk/fixture")))
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

    @Test
    fun `tar relative symlink confined after normalization is accepted`() {
        val payload = stateDirectory.resolve("tar-payload/gradle-fixture")
        Files.createDirectories(payload.resolve("bin"))
        Files.createDirectories(payload.resolve("lib"))
        Files.writeString(payload.resolve("bin/gradle"), "#!/bin/sh\n")
        Files.writeString(payload.resolve("lib/marker"), "ok")
        Files.createSymbolicLink(payload.resolve("lib/gradle-link"), Path.of("../bin/gradle"))
        val archive = stateDirectory.resolve("fixture.tar.gz")
        val result = ProcessBuilder("tar", "-C", payload.parent.toString(), "-czf", archive.toString(), payload.fileName.toString())
            .redirectErrorStream(true)
            .start()
        assertEquals(0, result.waitFor(), result.inputStream.bufferedReader().readText())
        val tarArtifact = artifact(archive).copy(archiveType = ToolchainArchiveType.TAR_GZ)

        val installed = installerFor(archive).install(
            UUID.randomUUID().toString(),
            tarArtifact,
            { false },
        ) { _, _ -> }

        val link = stateDirectory.resolve(installed.relativePath).resolve("lib/gradle-link")
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(Path.of("../bin/gradle"), Files.readSymbolicLink(link))
        assertTrue(installerFor(archive).verifyInstalled(installed.relativePath, installed.contentManifestSha256))
    }

    @Test
    fun `tar relative symlink escaping extraction root is rejected`() {
        val payload = stateDirectory.resolve("escape-payload/gradle-fixture")
        Files.createDirectories(payload.resolve("bin"))
        Files.createDirectories(payload.resolve("lib"))
        Files.writeString(payload.resolve("bin/gradle"), "#!/bin/sh\n")
        Files.writeString(payload.resolve("lib/marker"), "ok")
        Files.createSymbolicLink(payload.resolve("lib/escape"), Path.of("../../../outside"))
        val archive = stateDirectory.resolve("escape.tar.gz")
        val result = ProcessBuilder("tar", "-C", payload.parent.toString(), "-czf", archive.toString(), payload.fileName.toString())
            .redirectErrorStream(true)
            .start()
        assertEquals(0, result.waitFor(), result.inputStream.bufferedReader().readText())

        val failure = assertThrows(ToolchainInstallFailure::class.java) {
            installerFor(archive).install(
                UUID.randomUUID().toString(),
                artifact(archive).copy(archiveType = ToolchainArchiveType.TAR_GZ),
                { false },
            ) { _, _ -> }
        }

        assertEquals("ARCHIVE_LINK_INVALID", failure.code)
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

    private fun jdkArtifact(archive: Path) = artifact(archive).copy(
        artifactId = "jdk-fixture",
        component = ToolchainComponent.JDK,
        url = "https://github.com/adoptium/fixture.zip",
        installSubdirectory = "jdk/fixture",
        licenseId = "jdk-gpl-2.0-with-classpath-exception",
    )

    private fun androidPlatformArtifact(archive: Path) = artifact(archive).copy(
        artifactId = "android-platform-37.0-r02",
        component = ToolchainComponent.ANDROID_PLATFORM,
        version = "37.0-r02",
        url = "https://dl.google.com/android/repository/platform-37.0_r02.zip",
        installSubdirectory = "android/platforms/android-37.0",
        licenseId = "android-sdk-license",
        androidLocalPackage = AndroidLocalPackageMetadata(
            path = "platforms;android-37.0",
            apiLevel = "37.0",
            revisionMajor = 2,
            extensionLevel = 22,
            baseExtension = true,
            codename = "",
            layoutlibApi = 15,
            displayName = "Android SDK Platform 37.0",
        ),
    )

    private fun jdkTarPayload(directory: String): Path {
        val payload = stateDirectory.resolve("$directory/jdk-fixture")
        Files.createDirectories(payload.resolve("bin"))
        Files.createDirectories(payload.resolve("lib"))
        Files.writeString(payload.resolve("bin/java"), "#!/bin/sh\n")
        Files.writeString(payload.resolve("release"), "JAVA_VERSION=fixture\n")
        return payload
    }

    private fun tarGz(payload: Path, archive: Path) {
        val result = ProcessBuilder("tar", "-C", payload.parent.toString(), "-czf", archive.toString(), payload.fileName.toString())
            .redirectErrorStream(true)
            .start()
        assertEquals(0, result.waitFor(), result.inputStream.bufferedReader().readText())
    }

    private fun zip(path: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name)); output.write(value.encodeToByteArray()); output.closeEntry()
            }
        }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toLowerHex()
}
