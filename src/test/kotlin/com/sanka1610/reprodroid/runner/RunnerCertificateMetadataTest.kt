package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

class RunnerCertificateMetadataTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-09-08T00:00:00Z")
    private fun clock(time: Instant = now) = Clock.fixed(time, ZoneOffset.UTC)

    @Test
    fun metadataTracksExactActiveFilesAndRenewalWritesOneDurableAudit() {
        val security = security()
        val id = security.runnerId()
        val material = LocalCertificateAuthority(directory, clock()).initialize(id, "127.0.0.1",
            beforeActivate = { security.recordCertificates(it) })
        assertDoesNotThrow { security.verifyCertificates(material) }
        assertEquals(2, count("SELECT count(*) FROM security_certificates WHERE state='ACTIVE'"))
        assertEquals(1, count("SELECT count(*) FROM security_audit_events WHERE event_kind='LEAF_PUBLISHED'"))
        val later = now.plus(23, ChronoUnit.DAYS)
        val renewed = LocalCertificateAuthority(directory, clock(later)).loadOrRenew("127.0.0.1",
            beforeRenewalActivate = { security.recordCertificates(it) }, validateExisting = security::verifyCertificates)
        assertNotEquals(material.generationId, renewed.generationId)
        assertEquals(material.rootPin, renewed.rootPin)
        assertEquals(id, security.runnerId())
        assertDoesNotThrow { security.verifyCertificates(renewed) }
        assertEquals(2, count("SELECT count(*) FROM security_certificates WHERE state='ACTIVE'"))
        assertEquals(2, count("SELECT count(*) FROM security_certificates WHERE state='RETIRED'"))
        assertEquals(2, count("SELECT count(*) FROM security_audit_events WHERE event_kind='LEAF_PUBLISHED'"))
    }

    @Test
    fun metadataCorruptionIsRejectedBeforeRenewalCanRepairOrReplaceIt() {
        val security = security()
        LocalCertificateAuthority(directory, clock()).initialize(security.runnerId(), "127.0.0.1",
            beforeActivate = { security.recordCertificates(it) })
        val generation = Files.readString(directory.resolve("security/active-generation"))
        update("UPDATE security_certificates SET serial_hex='tampered' WHERE kind='ROOT'")
        var writes = 0
        assertThrows(IllegalStateException::class.java) {
            LocalCertificateAuthority(directory, clock(now.plus(23, ChronoUnit.DAYS))).loadOrRenew("127.0.0.1",
                beforeRenewalActivate = { writes++; security.recordCertificates(it) }, validateExisting = security::verifyCertificates)
        }
        assertEquals(0, writes)
        assertEquals(generation, Files.readString(directory.resolve("security/active-generation")))
        assertEquals(1, count("SELECT count(*) FROM security_audit_events WHERE event_kind='LEAF_PUBLISHED'"))
    }

    @Test
    fun duplicateActiveKindAndIdentityMismatchDoNotAuthenticateEstablishedFiles() {
        val security = security()
        val material = LocalCertificateAuthority(directory, clock()).initialize(security.runnerId(), "127.0.0.1",
            beforeActivate = { security.recordCertificates(it) })
        update("""
            INSERT INTO security_certificates(certificate_id,generation_id,kind,certificate_sha256,spki_sha256,
                serial_hex,not_before,not_after,relative_path,state,created_at)
            SELECT '${UUID.randomUUID()}','${UUID.randomUUID()}',kind,certificate_sha256,spki_sha256,
                serial_hex,not_before,not_after,relative_path,state,created_at
            FROM security_certificates WHERE kind='ROOT'
        """.trimIndent())
        assertThrows(IllegalStateException::class.java) { security.verifyCertificates(material) }
        update("UPDATE runner_identity SET runner_id='${UUID.randomUUID()}'")
        assertThrows(IllegalStateException::class.java) { security.verifyCertificates(material) }
    }

    @Test
    fun missingEstablishedIdentityAndMissingPreviouslyInitializedFilesCannotReinitializeSilently() {
        val security = security()
        LocalCertificateAuthority(directory, clock()).initialize(security.runnerId(), "127.0.0.1",
            beforeActivate = { security.recordCertificates(it) })
        Files.delete(directory.resolve("security/active-generation"))
        assertThrows(IllegalStateException::class.java) { security.requireUninitializedCertificates() }
        update("DELETE FROM runner_identity")
        assertThrows(IllegalStateException::class.java) { SQLiteJobStore(directory, clock()) }
        assertEquals(0, count("SELECT count(*) FROM runner_identity"))
        assertEquals(2, count("SELECT count(*) FROM security_certificates"))
    }

    private fun security(): RunnerSecurityStore { SQLiteJobStore(directory, clock()); return RunnerSecurityStore(directory, clock()) }
    private fun url() = "jdbc:sqlite:${directory.resolve("reprodroid-runner.sqlite3")}"
    private fun update(sql: String) = DriverManager.getConnection(url()).use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    private fun count(sql: String) = DriverManager.getConnection(url()).use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } } }
}
