package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Real X.509 and filesystem checks; not a substitute for an actual TLS connector test. */
class LocalCertificateAuthorityTest {
    @TempDir lateinit var directory: Path
    private val createdAt = Instant.parse("2026-09-08T00:00:00Z")

    @Test
    fun canonicalRunnerIdInitializesIndependentKeysFullChainAndRestrictedFiles() {
        val ca = caAt(createdAt)
        val material = ca.initialize(RUNNER_ID, "127.0.0.1")
        assertTrue(ca.isInitialized())
        assertEquals("SHA256withECDSA", material.root.sigAlgName)
        assertEquals("SHA256withECDSA", material.leaf.sigAlgName)
        assertFalse(material.root.publicKey.encoded.contentEquals(material.leaf.publicKey.encoded))
        assertEquals(createdAt.minus(5, ChronoUnit.MINUTES), material.root.notBefore.toInstant())
        assertEquals(createdAt.plus(5 * 365L, ChronoUnit.DAYS), material.root.notAfter.toInstant())
        assertEquals(createdAt.plus(30, ChronoUnit.DAYS), material.leaf.notAfter.toInstant())
        assertEquals(listOf(material.leaf, material.root), material.keyStore.getCertificateChain("runner-leaf").toList())
        assertTrue(material.leaf.subjectAlternativeNames.any { it[0] == 7 && it[1] == "127.0.0.1" })
        material.root.verify(material.root.publicKey)
        material.leaf.verify(material.root.publicKey)
        val loaded = caAt(createdAt.plusSeconds(1)).loadOrRenew("127.0.0.1")
        assertEquals(material.rootPin, loaded.rootPin)
        assertArrayEquals(material.root.encoded, loaded.root.encoded)
        assertArrayEquals(material.leaf.encoded, loaded.leaf.encoded)
        assertThrows(IllegalStateException::class.java) { ca.initialize(RUNNER_ID, "127.0.0.1") }
        Files.walk(directory.resolve("security")).use { paths -> paths.forEach { path ->
            val expected = if (Files.isDirectory(path)) "rwx------" else "rw-------"
            assertEquals(PosixFilePermissions.fromString(expected), Files.getPosixFilePermissions(path))
        } }
    }

    @Test
    fun renewalAtSevenDayBoundaryPublishesNewLeafUnderSameRootOnce() {
        val initial = caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        val initialGeneration = Files.readString(activePath())
        var audits = 0
        val beforeWindow = caAt(createdAt.plus(23, ChronoUnit.DAYS).minusSeconds(1))
            .loadOrRenew("runner.example", beforeRenewalActivate = { audits++ })
        assertEquals(initial.leaf.serialNumber, beforeWindow.leaf.serialNumber)
        assertEquals(0, audits)
        val renewed = caAt(createdAt.plus(23, ChronoUnit.DAYS)).loadOrRenew("runner.example", beforeRenewalActivate = { audits++ })
        assertNotEquals(initial.leaf.serialNumber, renewed.leaf.serialNumber)
        assertFalse(initial.leaf.publicKey.encoded.contentEquals(renewed.leaf.publicKey.encoded))
        assertArrayEquals(initial.root.encoded, renewed.root.encoded)
        assertEquals(initial.rootPin, renewed.rootPin)
        assertNotEquals(initialGeneration, Files.readString(activePath()))
        assertEquals(1, audits)
        caAt(createdAt.plus(23, ChronoUnit.DAYS).plusSeconds(1)).loadOrRenew("runner.example", beforeRenewalActivate = { audits++ })
        assertEquals(1, audits)
    }

    @Test
    fun failedPublicationCallbackDoesNotReplaceActiveTrustGeneration() {
        val initial = caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        val generation = Files.readString(activePath())
        assertThrows(IllegalStateException::class.java) {
            caAt(createdAt.plusSeconds(1)).initialize(RUNNER_ID, "runner.example", replaceRoot = true) {
                error("injected durable metadata failure")
            }
        }
        assertEquals(generation, Files.readString(activePath()))
        assertEquals(initial.rootPin, caAt(createdAt.plusSeconds(2)).loadOrRenew("runner.example").rootPin)
    }

    @Test
    fun explicitRootReplacementChangesPinAndPersistsAcrossRestart() {
        val first = caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        val replacement = caAt(createdAt.plusSeconds(1)).initialize(RUNNER_ID, "runner.example", replaceRoot = true)
        assertNotEquals(first.rootPin, replacement.rootPin)
        assertEquals(replacement.rootPin, caAt(createdAt.plusSeconds(2)).loadOrRenew("runner.example").rootPin)
    }

    @Test
    fun missingMaterialAndUnexpectedEndpointNeverGenerateReplacement() {
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
        assertFalse(Files.exists(activePath()))
        caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        val generation = Files.readString(activePath())
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("other.example") }
        Files.delete(generationPath().resolve("root-key.pk8"))
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
        assertEquals(generation, Files.readString(activePath()))
    }

    @Test
    fun symlinkKeyMaterialIsRejectedWithoutFollowingOrReplacingTarget() {
        caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        val key = generationPath().resolve("root-key.pk8")
        val target = directory.resolve("external-key.pk8")
        Files.move(key, target)
        val before = Files.readAllBytes(target)
        Files.createSymbolicLink(key, target)
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
        assertTrue(Files.isSymbolicLink(key))
        assertArrayEquals(before, Files.readAllBytes(target))
    }

    @Test
    fun certificateWithTrailingBytesIsNotAcceptedAsAValidWholeFile() {
        caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        Files.write(generationPath().resolve("root-cert.der"), byteArrayOf(1, 2, 3), StandardOpenOption.APPEND)
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
    }

    @Test
    fun worldReadableSecurityDirectoryIsRejected() {
        caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        Files.setPosixFilePermissions(directory.resolve("security"), PosixFilePermissions.fromString("rwxr-xr-x"))
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
    }

    @Test
    fun mismatchedPrivateKeyAndOversizedManifestFailWithoutNewGeneration() {
        caAt(createdAt).initialize(RUNNER_ID, "runner.example")
        val generation = generationPath()
        Files.copy(generation.resolve("leaf-key.pk8"), generation.resolve("root-key.pk8"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
        Files.writeString(activePath(), "a".repeat(65))
        assertThrows(Exception::class.java) { caAt(createdAt).loadOrRenew("runner.example") }
    }

    private fun caAt(instant: Instant) = LocalCertificateAuthority(directory, Clock.fixed(instant, ZoneOffset.UTC))
    private fun activePath() = directory.resolve("security/active-generation")
    private fun generationPath() = directory.resolve("security").resolve(Files.readString(activePath()))

    private companion object {
        const val RUNNER_ID = "b9c65fd2-ecac-487e-8c29-20a0cc9600b3"
    }
}
