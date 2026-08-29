package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.util.Locale

internal data class SourceScanResult(
    val detail: SourceScanDetailResponse,
    val canonicalBytes: ByteArray,
) {
    val requiresReview: Boolean get() = detail.summary.findingCount > 0
}

@Serializable
internal data class CanonicalSourceScanResult(
    val schemaVersion: Int,
    val resolvedCommitSha: String,
    val scannerVersion: String,
    val summary: SourceScanStatistics,
    val detectorCounts: List<SourceScanDetectorCount>,
    val findings: List<SourceScanFinding>,
)

internal fun canonicalSourceScanBytes(
    schemaVersion: Int,
    resolvedCommitSha: String,
    scannerVersion: String,
    summary: SourceScanStatistics,
    detectorCounts: List<SourceScanDetectorCount>,
    findings: List<SourceScanFinding>,
): ByteArray = SOURCE_SCAN_CANONICAL_JSON.encodeToString(
    CanonicalSourceScanResult(
        schemaVersion = schemaVersion,
        resolvedCommitSha = resolvedCommitSha,
        scannerVersion = scannerVersion,
        summary = summary,
        detectorCounts = detectorCounts,
        findings = findings,
    ),
).toByteArray(StandardCharsets.UTF_8)

internal fun sourceScanSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal class SourceScanner(
    private val nanoTime: () -> Long = System::nanoTime,
    private val timeout: Duration = Duration.ofSeconds(60),
) {
    fun scan(jobId: String, checkoutRoot: Path, resolvedCommitSha: String): SourceScanResult {
        val root = try {
            checkoutRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (_: Throwable) {
            invalid("The detached checkout is unavailable for source scanning.")
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            invalid("The detached checkout is not a regular directory.")
        }
        val deadline = nanoTime() + timeout.toNanos()
        val findings = mutableListOf<SourceScanFinding>()
        val pathEntries = mutableListOf<PathEntry>()
        var entryCount = 0
        var scannedFiles = 0
        var scannedBytes = 0L
        var skippedBinaryFiles = 0
        var skippedSymlinks = 0

        fun checkDeadline() {
            if (nanoTime() - deadline >= 0) {
                throw TrustedBuildFailure("SOURCE_SCAN_TIMEOUT", "Source scanning exceeded the fixed 60 second limit.")
            }
        }

        fun addFinding(finding: SourceScanFinding) {
            findings += finding
            if (findings.size > MAX_FINDINGS) {
                resourceLimit("Source scanning exceeded the fixed finding limit.")
            }
        }

        fun registerEntry(path: Path) {
            checkDeadline()
            entryCount++
            if (entryCount > MAX_ENTRIES) resourceLimit("Source scanning exceeded the fixed checkout entry limit.")
            val relative = safeRelativePath(root, path)
            val display = displayPath(relative)
            pathEntries += PathEntry(relative, display, Normalizer.normalize(relative, Normalizer.Form.NFC))
            if (!Normalizer.isNormalized(relative, Normalizer.Form.NFC)) {
                addFinding(SourceScanFinding(SourceScanDetectorId.UNICODE_NON_NFC, display))
            }
        }

        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkDeadline()
                    if (directory == root) return FileVisitResult.CONTINUE
                    val relative = safeRelativePath(root, directory)
                    if (relative == ".git") return FileVisitResult.SKIP_SUBTREE
                    if (!attributes.isDirectory || Files.isSymbolicLink(directory)) {
                        invalid("A checkout directory changed type during source scanning.")
                    }
                    registerEntry(directory)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    registerEntry(file)
                    if (attributes.isSymbolicLink || Files.isSymbolicLink(file)) {
                        skippedSymlinks++
                        return FileVisitResult.CONTINUE
                    }
                    if (!attributes.isRegularFile) {
                        invalid("The checkout contains an unsupported non-regular entry.")
                    }
                    val relative = safeRelativePath(root, file)
                    if (!isAllowedTextCandidate(relative)) return FileVisitResult.CONTINUE
                    val size = attributes.size()
                    if (size < 0 || size > MAX_FILE_BYTES) {
                        resourceLimit("A source scan candidate exceeded the fixed per-file limit.")
                    }
                    if (scannedBytes > MAX_TOTAL_BYTES - size) {
                        resourceLimit("Source scanning exceeded the fixed aggregate byte limit.")
                    }
                    scannedFiles++
                    scannedBytes += size
                    val bytes = readStableFile(file, attributes, size.toInt())
                    if (bytes.any { it == 0.toByte() }) {
                        skippedBinaryFiles++
                        return FileVisitResult.CONTINUE
                    }
                    val withoutBom = if (bytes.startsWithUtf8Bom()) bytes.copyOfRange(3, bytes.size) else bytes
                    val text = try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(withoutBom))
                            .toString()
                    } catch (_: Throwable) {
                        addFinding(
                            SourceScanFinding(
                                SourceScanDetectorId.TEXT_ENCODING_UNSUPPORTED,
                                displayPath(relative),
                            ),
                        )
                        return FileVisitResult.CONTINUE
                    }
                    detectText(relative, displayPath(relative), text, ::addFinding, ::checkDeadline)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, failure: java.io.IOException): FileVisitResult {
                    invalid("A checkout entry could not be read during source scanning.")
                }
            })
        } catch (failure: TrustedBuildFailure) {
            throw failure
        } catch (_: Throwable) {
            invalid("The checkout could not be scanned safely.")
        }

        pathEntries.groupBy(PathEntry::normalized).values
            .filter { it.size > 1 }
            .flatten()
            .forEach { entry ->
                addFinding(SourceScanFinding(SourceScanDetectorId.CANONICAL_PATH_COLLISION, entry.display))
            }

        val sortedFindings = findings.sortedWith(FINDING_ORDER)
        if (sortedFindings.size > MAX_FINDINGS) resourceLimit("Source scanning exceeded the fixed finding limit.")
        val detectorCounts = sortedFindings.groupingBy(SourceScanFinding::detectorId).eachCount()
            .entries.sortedBy { it.key.name }
            .map { SourceScanDetectorCount(it.key, it.value) }
        val summary = SourceScanStatistics(
            scannedFiles = scannedFiles,
            scannedBytes = scannedBytes,
            skippedBinaryFiles = skippedBinaryFiles,
            skippedSymlinks = skippedSymlinks,
            findingCount = sortedFindings.size,
        )
        val canonicalBytes = canonicalSourceScanBytes(
            schemaVersion = SCHEMA_VERSION,
            resolvedCommitSha = resolvedCommitSha,
            scannerVersion = SCANNER_VERSION,
            summary = summary,
            detectorCounts = detectorCounts,
            findings = sortedFindings,
        )
        val digest = sourceScanSha256(canonicalBytes)
        val detail = SourceScanDetailResponse(
            schemaVersion = SCHEMA_VERSION,
            jobId = jobId,
            resolvedCommitSha = resolvedCommitSha,
            scannerVersion = SCANNER_VERSION,
            resultSha256 = digest,
            summary = summary,
            detectorCounts = detectorCounts,
            findings = sortedFindings,
        )
        val responseBytes = PUBLIC_JSON.encodeToString(detail).toByteArray(StandardCharsets.UTF_8).size
        if (responseBytes > MAX_PUBLIC_RESPONSE_BYTES) {
            resourceLimit("The source scan response exceeded the fixed public response limit.")
        }
        return SourceScanResult(detail, canonicalBytes)
    }

    private fun readStableFile(file: Path, before: BasicFileAttributes, expectedSize: Int): ByteArray {
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val bytes = ByteArray(expectedSize)
        try {
            Files.newByteChannel(file, options).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) invalid("A source scan candidate changed size while it was read.")
                }
                if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                    invalid("A source scan candidate changed size while it was read.")
                }
            }
            val after = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (
                !after.isRegularFile || after.size() != before.size() ||
                after.lastModifiedTime() != before.lastModifiedTime() ||
                (before.fileKey() != null && after.fileKey() != before.fileKey())
            ) {
                invalid("A source scan candidate changed while it was read.")
            }
            return bytes
        } catch (failure: TrustedBuildFailure) {
            throw failure
        } catch (_: Throwable) {
            invalid("A source scan candidate could not be read safely.")
        }
    }

    private fun detectText(
        relativePath: String,
        displayPath: String,
        text: String,
        addFinding: (SourceScanFinding) -> Unit,
        checkDeadline: () -> Unit,
    ) {
        var offset = 0
        while (offset < text.length) {
            checkDeadline()
            val codePoint = text.codePointAt(offset)
            val detector = when {
                codePoint in BIDI_CODE_POINTS -> SourceScanDetectorId.UNICODE_BIDI_CONTROL
                codePoint in INVISIBLE_CODE_POINTS -> SourceScanDetectorId.UNICODE_INVISIBLE_FORMAT
                else -> null
            }
            detector?.let {
                val position = position(text, offset)
                addFinding(SourceScanFinding(it, displayPath, position.line, position.column))
            }
            offset += Character.charCount(codePoint)
        }

        var lineStart = 0
        var lineNumber = 1
        while (lineStart <= text.length) {
            checkDeadline()
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            if (!Normalizer.isNormalized(line, Normalizer.Form.NFC)) {
                val difference = firstCodePointDifference(line, Normalizer.normalize(line, Normalizer.Form.NFC))
                addFinding(
                    SourceScanFinding(
                        SourceScanDetectorId.UNICODE_NON_NFC,
                        displayPath,
                        lineNumber,
                        difference + 1,
                    ),
                )
            }
            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
            lineNumber++
        }

        val lowerPath = relativePath.lowercase(Locale.ROOT)
        val extension = lowerPath.substringAfterLast('.', missingDelimiterValue = "")
        val jvmOrBuild = extension in setOf("kt", "kts", "java", "gradle", "groovy")
        val native = extension in setOf("c", "cc", "cpp", "cxx", "h", "hpp")
        val groovyOrGradle = extension in setOf("gradle", "groovy")
        val networkScript = extension in setOf("sh", "bash", "gradle", "groovy", "kts", "ps1", "bat", "cmd") ||
            lowerPath.substringAfterLast('/') == "gradlew"

        if (jvmOrBuild) {
            PROCESS_JVM_PATTERNS.forEach { detectMatches(it, SourceScanDetectorId.PROCESS_EXEC_API, displayPath, text, addFinding, checkDeadline) }
            DYNAMIC_JVM_PATTERNS.forEach { detectMatches(it, SourceScanDetectorId.DYNAMIC_NATIVE_LOAD_API, displayPath, text, addFinding, checkDeadline) }
        }
        if (groovyOrGradle) {
            detectMatches(GROOVY_EXECUTE_PATTERN, SourceScanDetectorId.PROCESS_EXEC_API, displayPath, text, addFinding, checkDeadline)
        }
        if (native) {
            PROCESS_NATIVE_PATTERNS.forEach { detectMatches(it, SourceScanDetectorId.PROCESS_EXEC_API, displayPath, text, addFinding, checkDeadline) }
            DYNAMIC_NATIVE_PATTERNS.forEach { detectMatches(it, SourceScanDetectorId.DYNAMIC_NATIVE_LOAD_API, displayPath, text, addFinding, checkDeadline) }
        }
        if (networkScript) {
            detectMatches(NETWORK_PATTERN, SourceScanDetectorId.NETWORK_DOWNLOAD_COMMAND, displayPath, text, addFinding, checkDeadline)
        }
    }

    private fun detectMatches(
        regex: Regex,
        detectorId: SourceScanDetectorId,
        displayPath: String,
        text: String,
        addFinding: (SourceScanFinding) -> Unit,
        checkDeadline: () -> Unit,
    ) {
        regex.findAll(text).forEach { match ->
            checkDeadline()
            val position = position(text, match.range.first)
            addFinding(SourceScanFinding(detectorId, displayPath, position.line, position.column))
        }
    }

    private fun safeRelativePath(root: Path, path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) invalid("A checkout entry escaped the detached checkout root.")
        val relative = root.relativize(normalized)
        if (relative.isAbsolute || relative.nameCount == 0) invalid("A checkout entry has an invalid relative path.")
        val components = (0 until relative.nameCount).map { relative.getName(it).toString() }
        if (components.any { it.isEmpty() || it == "." || it == ".." }) {
            invalid("A checkout entry has an invalid relative path.")
        }
        return components.joinToString("/")
    }

    private fun displayPath(relative: String): String {
        val display = if (CREDENTIAL_PATTERN.containsMatchIn(relative)) {
            "<redacted-path>"
        } else {
            buildString {
                var offset = 0
                while (offset < relative.length) {
                    val codePoint = relative.codePointAt(offset)
                    if (isUnsafeDisplayCodePoint(codePoint)) {
                        val width = if (codePoint <= 0xffff) 4 else 6
                        append("<U+")
                        append(codePoint.toString(16).uppercase(Locale.ROOT).padStart(width, '0'))
                        append('>')
                    } else {
                        appendCodePoint(codePoint)
                    }
                    offset += Character.charCount(codePoint)
                }
            }
        }
        if (display.toByteArray(StandardCharsets.UTF_8).size > MAX_DISPLAY_PATH_BYTES) {
            resourceLimit("An escaped source scan display path exceeded the fixed limit.")
        }
        return display
    }

    private fun isAllowedTextCandidate(relative: String): Boolean {
        val basename = relative.substringAfterLast('/').lowercase(Locale.ROOT)
        if (basename in ALLOWED_BASENAMES) return true
        val dot = basename.lastIndexOf('.')
        if (dot < 0) return false
        return basename.substring(dot) in ALLOWED_EXTENSIONS
    }

    private fun isUnsafeDisplayCodePoint(codePoint: Int): Boolean =
        codePoint <= 0x1f || codePoint in 0x7f..0x9f || codePoint in BIDI_CODE_POINTS || codePoint in INVISIBLE_CODE_POINTS

    private fun position(text: String, utf16Offset: Int): Position {
        val lineStart = text.lastIndexOf('\n', utf16Offset - 1).let { if (it < 0) 0 else it + 1 }
        val line = text.countBefore(utf16Offset, '\n') + 1
        val column = text.codePointCount(lineStart, utf16Offset) + 1
        return Position(line, column)
    }

    private fun firstCodePointDifference(left: String, right: String): Int {
        val leftPoints = left.codePoints().toArray()
        val rightPoints = right.codePoints().toArray()
        val limit = minOf(leftPoints.size, rightPoints.size)
        for (index in 0 until limit) if (leftPoints[index] != rightPoints[index]) return index
        return limit
    }

    private fun String.countBefore(endExclusive: Int, expected: Char): Int {
        var count = 0
        for (index in 0 until endExclusive) if (this[index] == expected) count++
        return count
    }

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= 3 && this[0] == 0xef.toByte() && this[1] == 0xbb.toByte() && this[2] == 0xbf.toByte()

    private fun invalid(message: String): Nothing = throw TrustedBuildFailure("SOURCE_SCAN_INVALID", message)

    private fun resourceLimit(message: String): Nothing =
        throw TrustedBuildFailure("SOURCE_SCAN_RESOURCE_LIMIT_EXCEEDED", message)

    private data class PathEntry(val raw: String, val display: String, val normalized: String)
    private data class Position(val line: Int, val column: Int)

    private companion object {
        const val SCHEMA_VERSION = 1
        const val SCANNER_VERSION = "reprodroid-static-v1"
        const val MAX_ENTRIES = 50_000
        const val MAX_FILE_BYTES = 4L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
        const val MAX_FINDINGS = 5_000
        const val MAX_PUBLIC_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_DISPLAY_PATH_BYTES = 1_024

        val ALLOWED_EXTENSIONS = setOf(
            ".kt", ".kts", ".java", ".gradle", ".groovy", ".xml", ".aidl", ".smali",
            ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".sh", ".bash", ".py", ".js",
            ".mjs", ".cjs", ".ts", ".ps1", ".bat", ".cmd", ".properties", ".toml",
            ".json", ".yaml", ".yml",
        )
        val ALLOWED_BASENAMES = setOf(
            "gradlew", "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties",
        )
        val BIDI_CODE_POINTS = setOf(0x061c, 0x200e, 0x200f) + (0x202a..0x202e) + (0x2066..0x2069)
        val INVISIBLE_CODE_POINTS = setOf(0x00ad, 0x200b, 0x200c, 0x200d, 0x2060, 0xfeff)
        val PROCESS_JVM_PATTERNS = listOf(
            Regex("""\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\("""),
            Regex("""\bProcessBuilder\s*\("""),
            Regex("""^\s*(?:project\s*\.\s*)?exec\s*\{""", setOf(RegexOption.MULTILINE)),
            Regex("""\bproviders\s*\.\s*exec\s*\{"""),
            Regex("""\b(?:ExecOperations|JavaExec|Exec)\b"""),
        )
        val PROCESS_NATIVE_PATTERNS = listOf(Regex("""\b(?:system|popen|execve)\s*\("""))
        val GROOVY_EXECUTE_PATTERN = Regex("""\.\s*execute\s*\(""")
        val DYNAMIC_JVM_PATTERNS = listOf(Regex("""\bSystem\s*\.\s*(?:load|loadLibrary)\s*\("""))
        val DYNAMIC_NATIVE_PATTERNS = listOf(Regex("""\b(?:dlopen|LoadLibraryA|LoadLibraryW)\s*\("""))
        val NETWORK_PATTERN = Regex("""(?:^|[;&|]\s*|\s)(?:curl|wget)\s+""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val CREDENTIAL_PATTERN = Regex(
            """(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|sk-[A-Za-z0-9_-]{20,})""",
        )
        val FINDING_ORDER = compareBy<SourceScanFinding>(
            { it.detectorId.name },
            SourceScanFinding::displayPath,
            { it.line != null },
            { it.line ?: 0 },
            { it.column ?: 0 },
        )
        val PUBLIC_JSON = Json { explicitNulls = false; encodeDefaults = true }
    }
}

private val SOURCE_SCAN_CANONICAL_JSON = Json { explicitNulls = false; encodeDefaults = true }

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
