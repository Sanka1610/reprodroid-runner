package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SandboxOutputImportTest {
    @TempDir lateinit var directory: Path

    @Test fun `dependencies hash confined regular files and missing tree is empty`() {
        val root = Files.createDirectories(directory.resolve("modules/group"))
        Files.writeString(root.resolve("test.jar"), "bytes")
        val imported = SandboxOutputImport(root.parent, SandboxImportLimits.DEPENDENCIES).dependencies()
        assertEquals(listOf(ManifestFile("group/test.jar", 5, sha256(root.resolve("test.jar")))), imported)
        assertTrue(SandboxOutputImport(directory.resolve("absent/tree"), SandboxImportLimits.DEPENDENCIES).dependencies().isEmpty())
    }

    @Test fun `file tree root and outside ancestors reject symlinks before content read`() {
        val root = Files.createDirectories(directory.resolve("modules"))
        val outside = Files.writeString(directory.resolve("secret"), "secret")
        Files.createSymbolicLink(root.resolve("external.jar"), outside)
        invalid(root)
        val linkedRoot = Files.createSymbolicLink(directory.resolve("alias"), root)
        invalid(linkedRoot)
        invalid(linkedRoot.resolve("absent/tree"))
    }

    @Test fun `entry file aggregate bytes and deadline limits are enforced`() {
        val root = Files.createDirectories(directory.resolve("modules"))
        Files.writeString(root.resolve("a.jar"), "1234")
        Files.writeString(root.resolve("b.jar"), "1234")
        val defaults = SandboxImportLimits.DEPENDENCIES
        listOf(defaults.copy(entries = 1), defaults.copy(regularFiles = 1), defaults.copy(fileBytes = 3), defaults.copy(totalBytes = 7)).forEach {
            assertEquals("SANDBOX_OUTPUT_LIMIT_EXCEEDED", assertThrows(TrustedBuildFailure::class.java) { SandboxOutputImport(root, it).dependencies() }.code)
        }
        var clock = 0L
        val importer = SandboxOutputImport(root, defaults.copy(timeoutNanos = 1)) { clock++ }
        assertEquals("SANDBOX_OUTPUT_LIMIT_EXCEEDED", assertThrows(TrustedBuildFailure::class.java) { importer.dependencies() }.code)
    }

    @Test fun `APK staging copies atomically and removes partial outputs on limit failure`() {
        val root = Files.createDirectories(directory.resolve("outputs"))
        val destination = Files.createDirectories(directory.resolve("private"))
        Files.writeString(root.resolve("app.apk"), "APK fixture")
        val copied = SandboxOutputImport(root, SandboxImportLimits.APK).importApks(destination).single()
        assertEquals("APK fixture", Files.readString(copied.first))
        assertEquals("app.apk", copied.second.path)
        assertFalse(copied.first.toString().endsWith(".partial"))
        val failedDestination = Files.createDirectories(directory.resolve("failed"))
        assertThrows(TrustedBuildFailure::class.java) {
            SandboxOutputImport(root, SandboxImportLimits.APK.copy(totalBytes = 1)).importApks(failedDestination)
        }
        Files.list(failedDestination).use { assertEquals(0, it.count()) }
    }

    @Test fun `generic APK selection accepts one renamed output and fails closed for ambiguous outputs`() {
        val staging = Files.createDirectories(directory.resolve("generic-staging"))
        fun candidate(name: String): Pair<Path, ManifestFile> {
            val path = Files.writeString(staging.resolve(name), name)
            return path to ManifestFile("release/$name", Files.size(path), sha256(path))
        }

        val renamed = candidate("app-release-unsigned.apk")
        assertEquals(
            listOf(renamed),
            selectGenericImportedApk(listOf(renamed), "NewPipe_v0.29.1.apk"),
        )

        val preferred = candidate("FairEmail-v1.2333a-github-release.apk")
        val other = candidate("FairEmail-v1.2333a-play-release.apk")
        assertEquals(
            listOf(preferred),
            selectGenericImportedApk(listOf(preferred, other), preferred.first.fileName.toString()),
        )
        assertTrue(Files.isRegularFile(preferred.first))
        assertFalse(Files.exists(other.first))

        val first = candidate("first.apk")
        val second = candidate("second.apk")
        assertTrue(selectGenericImportedApk(listOf(first, second), "absent.apk").isEmpty())
        assertFalse(Files.exists(first.first))
        assertFalse(Files.exists(second.first))
    }

    private fun invalid(root: Path) = assertEquals("SANDBOX_OUTPUT_INVALID", assertThrows(TrustedBuildFailure::class.java) {
        SandboxOutputImport(root, SandboxImportLimits.DEPENDENCIES).dependencies()
    }.code)

    @Test fun `FIFO is rejected before opening it and file mutation invalidates the complete result`() {
        val root = Files.createDirectories(directory.resolve("fifo"))
        val fifo = root.resolve("blocked.jar")
        val command = ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start()
        assertTrue(command.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, command.exitValue())
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2)) { invalid(root) }
        val mutableRoot = Files.createDirectories(directory.resolve("mutable"))
        val mutableFile = Files.writeString(mutableRoot.resolve("a.jar"), "original")
        var changed = false
        val importer = SandboxOutputImport(mutableRoot, SandboxImportLimits.DEPENDENCIES) {
            if (!changed && Thread.currentThread().stackTrace.any { it.methodName == "readFile" }) {
                Files.writeString(mutableFile, "a changed longer file")
                changed = true
            }
            0L
        }
        assertEquals("SANDBOX_OUTPUT_INVALID", assertThrows(TrustedBuildFailure::class.java) { importer.dependencies() }.code)
        assertTrue(changed)
    }

    @Test fun `ancestor replacement never redirects an already pinned APK import to outside content`() {
        val ancestor = Files.createDirectories(directory.resolve("ancestor"))
        val output = Files.createDirectories(ancestor.resolve("outputs"))
        val original = Files.writeString(output.resolve("app.apk"), "original APK fixture")
        val expectedHash = sha256(original)
        val outside = Files.createDirectories(directory.resolve("outside/outputs"))
        Files.writeString(outside.resolve("app.apk"), "outside secret")
        val destination = Files.createDirectories(directory.resolve("staging"))
        var replaced = false
        val importer = SandboxOutputImport(output, SandboxImportLimits.APK) {
            if (!replaced && Thread.currentThread().stackTrace.any { it.methodName == "readFile" }) {
                Files.move(ancestor, directory.resolve("pinned-ancestor"))
                Files.createSymbolicLink(ancestor, outside.parent)
                replaced = true
            }
            0L
        }
        val result = importer.importApks(destination).single()
        assertTrue(replaced)
        assertEquals(expectedHash, result.second.sha256)
        assertEquals("original APK fixture", Files.readString(result.first))
        // A subsequent import has no old directory descriptors and must reject the replaced ancestor.
        assertEquals("SANDBOX_OUTPUT_INVALID", assertThrows(TrustedBuildFailure::class.java) {
            SandboxOutputImport(output, SandboxImportLimits.APK).importApks(destination)
        }.code)
        Files.list(destination).use { assertEquals(1, it.count()) }
    }

    @Test fun `actual opt-in character device is rejected before dependency or APK content is opened`() {
        val fixture = System.getenv("REPRODROID_TEST_DEVICE_DIRECTORY")
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture != null, "No explicit device-node fixture supplied")
        val root = Path.of(requireNotNull(fixture))
        val device = root.resolve("null.apk")
        val mode = Files.getAttribute(device, "unix:mode", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Int
        assertEquals(0x2000, mode and 0xf000) // Real character device, not a symlink or a regular-file mock.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2)) {
            invalid(root)
            val destination = Files.createDirectories(directory.resolve("device-staging"))
            assertEquals("SANDBOX_OUTPUT_INVALID", assertThrows(TrustedBuildFailure::class.java) {
                SandboxOutputImport(root, SandboxImportLimits.APK).importApks(destination)
            }.code)
            Files.list(destination).use { assertEquals(0, it.count()) }
        }
    }
}
