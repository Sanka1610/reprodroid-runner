package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class SourceScannerTest {
    @TempDir
    lateinit var checkout: Path

    @Test
    fun `zero findings produces a stable completed result`() {
        checkout.resolve("src/App.kt").also { it.parent.createDirectories() }.writeText("class App\n")

        val first = SourceScanner().scan("job-a", checkout, COMMIT)
        val second = SourceScanner().scan("job-b", checkout, COMMIT)

        assertEquals(1, first.detail.summary.scannedFiles)
        assertEquals(0, first.detail.summary.findingCount)
        assertFalse(first.requiresReview)
        assertEquals(first.detail.resultSha256, second.detail.resultSha256)
        assertEquals(first.canonicalBytes.toList(), second.canonicalBytes.toList())
    }

    @Test
    fun `bidi invisible and non nfc findings use code point locations`() {
        checkout.resolve("src/Test.kt").also { it.parent.createDirectories() }
            .writeText("val a = \u202E1\nval cafe\u0301 = \u200B2\n")

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(1, result.detail.detectorCounts.single { it.detectorId == SourceScanDetectorId.UNICODE_BIDI_CONTROL }.count)
        assertEquals(1, result.detail.detectorCounts.single { it.detectorId == SourceScanDetectorId.UNICODE_INVISIBLE_FORMAT }.count)
        assertEquals(1, result.detail.detectorCounts.single { it.detectorId == SourceScanDetectorId.UNICODE_NON_NFC }.count)
        assertTrue(result.detail.findings.all { (it.line == null) == (it.column == null) })
    }

    @Test
    fun `leading bom is removed and interior bom is reported`() {
        checkout.resolve("Bom.kt").writeText("\uFEFFclass Bom { val marker = '\uFEFF' }\n")

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(
            1,
            result.detail.findings.count { it.detectorId == SourceScanDetectorId.UNICODE_INVISIBLE_FORMAT },
        )
    }

    @Test
    fun `invalid utf8 creates one encoding finding and nul candidate is skipped`() {
        checkout.resolve("Invalid.kt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        checkout.resolve("Binary.kt").writeBytes(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(2, result.detail.summary.scannedFiles)
        assertEquals(1, result.detail.summary.skippedBinaryFiles)
        assertEquals(
            1,
            result.detail.findings.count { it.detectorId == SourceScanDetectorId.TEXT_ENCODING_UNSUPPORTED },
        )
    }

    @Test
    fun `process and network patterns are language scoped`() {
        checkout.resolve("Build.kt").writeText("val process = ProcessBuilder(\"true\")\n")
        checkout.resolve("notes.json").writeText("{\"text\":\"ProcessBuilder( curl https://example.test\"}\n")
        checkout.resolve("script.sh").writeText("curl https://example.test/file\n")

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(1, result.detail.findings.count { it.detectorId == SourceScanDetectorId.PROCESS_EXEC_API })
        assertEquals(1, result.detail.findings.count { it.detectorId == SourceScanDetectorId.NETWORK_DOWNLOAD_COMMAND })
    }

    @Test
    fun `git tree is excluded and symbolic links are never followed`() {
        checkout.resolve(".git/hooks").createDirectories()
        checkout.resolve(".git/hooks/unsafe.sh").writeText("curl https://example.test\n")
        checkout.resolve("outside.kt").writeText("ProcessBuilder(\"true\")")
        Files.createSymbolicLink(checkout.resolve("linked.kt"), checkout.resolve("outside.kt"))

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(1, result.detail.summary.skippedSymlinks)
        assertEquals(1, result.detail.findings.count { it.detectorId == SourceScanDetectorId.PROCESS_EXEC_API })
        assertTrue(result.detail.findings.none { it.displayPath.startsWith(".git/") })
    }

    @Test
    fun `canonical path collision reports every colliding entry`() {
        checkout.resolve("caf\u00E9.kt").writeText("class A")
        checkout.resolve("cafe\u0301.kt").writeText("class B")

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(
            2,
            result.detail.findings.count { it.detectorId == SourceScanDetectorId.CANONICAL_PATH_COLLISION },
        )
        assertEquals(
            1,
            result.detail.findings.count { it.detectorId == SourceScanDetectorId.UNICODE_NON_NFC && it.line == null },
        )
    }

    @Test
    fun `unsafe display path is escaped without returning the raw code point`() {
        checkout.resolve("bad\u202Ename.kt").writeText("val process = ProcessBuilder(\"true\")")

        val result = SourceScanner().scan("job", checkout, COMMIT)
        val finding = result.detail.findings.single { it.detectorId == SourceScanDetectorId.PROCESS_EXEC_API }

        assertFalse(finding.displayPath.contains('\u202E'))
        assertTrue(finding.displayPath.contains("<U+202E>"))
    }

    @Test
    fun `redacted paths preserve per file finding cardinality`() {
        checkout.resolve("ghp_12345678901234567890.kt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        checkout.resolve("ghp_abcdefghijklmnopqrst.kt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))

        val result = SourceScanner().scan("job", checkout, COMMIT)

        assertEquals(2, result.detail.summary.findingCount)
        assertEquals(listOf("<redacted-path>", "<redacted-path>"), result.detail.findings.map { it.displayPath })
    }

    @Test
    fun `single file limit fails closed`() {
        checkout.resolve("Large.kt").writeBytes(ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() })

        val failure = assertThrows(TrustedBuildFailure::class.java) {
            SourceScanner().scan("job", checkout, COMMIT)
        }

        assertEquals("SOURCE_SCAN_RESOURCE_LIMIT_EXCEEDED", failure.code)
    }

    @Test
    fun `timeout fails closed`() {
        checkout.resolve("Test.kt").writeText("class Test")
        val ticks = AtomicLong(0)
        val scanner = SourceScanner(
            nanoTime = { ticks.getAndAdd(Duration.ofSeconds(2).toNanos()) },
            timeout = Duration.ofSeconds(1),
        )

        val failure = assertThrows(TrustedBuildFailure::class.java) {
            scanner.scan("job", checkout, COMMIT)
        }

        assertEquals("SOURCE_SCAN_TIMEOUT", failure.code)
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
