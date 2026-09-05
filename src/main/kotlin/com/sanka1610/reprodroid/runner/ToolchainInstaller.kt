package com.sanka1610.reprodroid.runner

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal class ToolchainCancelledException : RuntimeException("Toolchain installation was cancelled.")

internal fun interface ToolchainProgressSink {
    fun update(state: ToolchainInstallationState, downloadedBytes: Long)
}

internal fun interface ToolchainArchiveDownloader {
    fun download(artifact: ToolchainCatalogArtifact, destination: Path, cancelled: () -> Boolean, onProgress: (Long) -> Unit)
}

internal class ToolchainInstaller(
    private val stateDirectory: Path,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val archiveDownloader: ToolchainArchiveDownloader? = null,
) {
    private val toolchainRoot = stateDirectory.resolve("toolchains")
    private val stagingRoot = stateDirectory.resolve("toolchain-staging")

    fun install(
        installationId: String,
        artifact: ToolchainCatalogArtifact,
        cancelled: () -> Boolean,
        progress: ToolchainProgressSink,
    ): InstalledToolchain {
        val owned = stagingRoot.resolve(installationId).resolve(artifact.artifactId)
        deleteTree(owned)
        owned.createDirectories()
        val archive = owned.resolve("archive.download")
        progress.update(ToolchainInstallationState.DOWNLOADING, 0)
        val onDownloadProgress: (Long) -> Unit = { bytes -> progress.update(ToolchainInstallationState.DOWNLOADING, bytes) }
        archiveDownloader?.download(artifact, archive, cancelled, onDownloadProgress)
            ?: download(artifact.url, archive, artifact.archiveSizeBytes.toLong(), cancelled, onDownloadProgress)
        cancelled.throwIfCancelled()
        progress.update(ToolchainInstallationState.VERIFYING_ARCHIVE, artifact.archiveSizeBytes.toLong())
        verifySha256(archive, artifact.archiveSha256)
        if (artifact.signaturePolicy == ToolchainSignaturePolicy.OPENPGP_REQUIRED) {
            verifyOpenPgp(artifact, archive, owned, cancelled)
        }
        val extractRoot = owned.resolve("extract")
        extractRoot.createDirectories()
        progress.update(ToolchainInstallationState.EXTRACTING, artifact.archiveSizeBytes.toLong())
        when (artifact.archiveType) {
            ToolchainArchiveType.ZIP -> extractZip(archive, extractRoot, artifact.maximumExpandedBytes.toLong(), artifact.maximumEntries, cancelled)
            ToolchainArchiveType.TAR_GZ -> extractTarGz(archive, extractRoot, artifact.maximumExpandedBytes.toLong(), artifact.maximumEntries, cancelled)
        }
        cancelled.throwIfCancelled()
        val payload = singleRootOrSelf(extractRoot)
        progress.update(ToolchainInstallationState.VERIFYING_CONTENT, artifact.archiveSizeBytes.toLong())
        restoreExecutableEntries(artifact.component, payload)
        validateMetadata(artifact, payload)
        val manifest = createContentManifest(payload, cancelled)
        val manifestPath = payload.resolve(CONTENT_MANIFEST_NAME)
        Files.writeString(manifestPath, manifest.text, StandardCharsets.UTF_8)
        fsyncFile(manifestPath)
        fsyncTree(payload)
        makeReadOnly(payload)
        makeRootOwnerWritable(payload)
        val destination = toolchainRoot.resolve(artifact.installSubdirectory).normalize()
        require(destination.startsWith(toolchainRoot.normalize()))
        destination.parent.createDirectories()
        progress.update(ToolchainInstallationState.PUBLISHING, artifact.archiveSizeBytes.toLong())
        if (destination.exists(LinkOption.NOFOLLOW_LINKS)) {
            throw ToolchainInstallFailure("TOOLCHAIN_DESTINATION_EXISTS", "The final toolchain path already exists and requires reconciliation.")
        }
        try {
            Files.move(payload, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            throw ToolchainInstallFailure("ATOMIC_PUBLISH_UNSUPPORTED", "The toolchain store does not support same-filesystem atomic publish.")
        }
        makeReadOnly(destination)
        fsyncDirectory(destination.parent)
        archive.deleteIfExists()
        deleteTree(owned)
        return InstalledToolchain(
            artifact.artifactId,
            artifact.component,
            artifact.version,
            artifact.archiveSha256,
            manifest.sha256,
            directoryBytes(destination),
            stateDirectory.relativize(destination).toString(),
        )
    }

    fun remove(relativePath: String): Long {
        val target = stateDirectory.resolve(relativePath).normalize()
        require(target.startsWith(toolchainRoot.normalize()) && target != toolchainRoot.normalize())
        val bytes = if (target.exists(LinkOption.NOFOLLOW_LINKS)) directoryBytes(target) else 0L
        makeOwnerWritable(target)
        deleteTree(target)
        return bytes
    }

    fun removeOwnedStaging(installationId: String) {
        require(CANONICAL_UUID.matches(installationId))
        deleteTree(stagingRoot.resolve(installationId))
    }

    fun verifyInstalled(relativePath: String, expectedManifestSha256: String): Boolean = runCatching {
        val target = stateDirectory.resolve(relativePath).normalize()
        require(target.startsWith(toolchainRoot.normalize()) && target != toolchainRoot.normalize())
        val manifestPath = target.resolve(CONTENT_MANIFEST_NAME)
        if (!target.isDirectory(LinkOption.NOFOLLOW_LINKS) || !manifestPath.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching false
        }
        val storedText = Files.readString(manifestPath, StandardCharsets.UTF_8)
        if (sha256(storedText.encodeToByteArray()) != expectedManifestSha256) return@runCatching false
        val observed = createContentManifest(target) { false }
        storedText == observed.text && observed.sha256 == expectedManifestSha256
    }.getOrDefault(false)

    private fun download(url: String, destination: Path, expectedBytes: Long, cancelled: () -> Boolean, onProgress: (Long) -> Unit) {
        var current = URI(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireTrustedDownloadUri(current)
            val response = httpClient.send(
                HttpRequest.newBuilder(current).timeout(Duration.ofMinutes(15)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            if (response.statusCode() in 300..399) {
                response.body().close()
                if (redirectCount == MAX_REDIRECTS) throw ToolchainInstallFailure("DOWNLOAD_REDIRECT_INVALID", "Too many redirects.")
                val location = response.headers().firstValue("location").orElseThrow {
                    ToolchainInstallFailure("DOWNLOAD_REDIRECT_INVALID", "A redirect omitted Location.")
                }
                current = current.resolve(location)
                return@repeat
            }
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                response.body().close()
                throw ToolchainInstallFailure("DOWNLOAD_HTTP_ERROR", "Trusted source returned HTTP ${response.statusCode()}.")
            }
            val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1)
            if (contentLength != -1L && contentLength != expectedBytes) {
                response.body().close()
                throw ToolchainInstallFailure("DOWNLOAD_SIZE_MISMATCH", "Content-Length does not match the catalog.")
            }
            response.body().use { input ->
                Files.newOutputStream(destination).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        cancelled.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > expectedBytes) throw ToolchainInstallFailure("DOWNLOAD_SIZE_MISMATCH", "Download exceeds catalog size.")
                        output.write(buffer, 0, read)
                        onProgress(total)
                    }
                    if (total != expectedBytes) throw ToolchainInstallFailure("DOWNLOAD_SIZE_MISMATCH", "Downloaded size does not match the catalog.")
                }
            }
            fsyncFile(destination)
            return
        }
        error("unreachable")
    }

    private fun verifyOpenPgp(artifact: ToolchainCatalogArtifact, archive: Path, owned: Path, cancelled: () -> Boolean) {
        val signature = owned.resolve("archive.sig")
        downloadUnknownSize(requireNotNull(artifact.signatureUrl), signature, 1_048_576, cancelled)
        val key = owned.resolve("trusted-publisher-key.gpg")
        requireNotNull(javaClass.getResourceAsStream(requireNotNull(artifact.signingKeyResource))).use { input ->
            Files.write(key, java.util.Base64.getMimeDecoder().decode(input.readBytes()))
        }
        val result = ProcessBuilder("gpgv", "--keyring", key.toString(), signature.toString(), archive.toString())
            .redirectErrorStream(true)
            .start()
        val deadline = System.nanoTime() + SIGNATURE_TIMEOUT_NANOS
        while (result.isAlive) {
            if (cancelled()) {
                result.destroyForcibly()
                throw ToolchainCancelledException()
            }
            if (System.nanoTime() >= deadline) {
                result.destroyForcibly()
                throw ToolchainInstallFailure("PUBLISHER_SIGNATURE_TIMEOUT", "Publisher signature verification timed out.")
            }
            Thread.sleep(25)
        }
        val output = result.inputStream.bufferedReader().use { it.readText().take(4096) }
        if (result.exitValue() != 0 || requireNotNull(artifact.signingKeyFingerprint) !in output.replace(" ", "")) {
            throw ToolchainInstallFailure("PUBLISHER_SIGNATURE_INVALID", "Publisher signature verification failed.")
        }
    }

    private fun downloadUnknownSize(url: String, destination: Path, maximumBytes: Long, cancelled: () -> Boolean) {
        var uri = URI(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            requireTrustedDownloadUri(uri)
            val response = httpClient.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() in 300..399) {
                response.body().close()
                if (redirectCount == MAX_REDIRECTS) throw ToolchainInstallFailure("SIGNATURE_DOWNLOAD_FAILED", "Too many signature redirects.")
                uri = uri.resolve(response.headers().firstValue("location").orElseThrow {
                    ToolchainInstallFailure("SIGNATURE_DOWNLOAD_FAILED", "Signature redirect omitted Location.")
                })
                return@repeat
            }
            if (response.statusCode() != 200) throw ToolchainInstallFailure("SIGNATURE_DOWNLOAD_FAILED", "Publisher signature download failed.")
            response.body().use { input ->
                Files.newOutputStream(destination).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        cancelled.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maximumBytes) throw ToolchainInstallFailure("SIGNATURE_TOO_LARGE", "Publisher signature exceeds the size limit.")
                        output.write(buffer, 0, read)
                    }
                }
            }
            return
        }
        error("unreachable")
    }

    private fun extractZip(archive: Path, root: Path, limit: Long, maximumEntries: Int, cancelled: () -> Boolean) {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            var expanded = 0L
            var entries = 0
            while (true) {
                cancelled.throwIfCancelled()
                val entry = zip.nextEntry ?: break
                if (++entries > maximumEntries) throw ToolchainInstallFailure("ARCHIVE_ENTRY_LIMIT_EXCEEDED", "Archive entry count exceeds the catalog limit.")
                val destination = safeDestination(root, entry.name)
                if (entry.isDirectory) {
                    destination.createDirectories()
                } else {
                    safeParentDirectories(root, destination.parent)
                    Files.newOutputStream(destination).use { output ->
                        expanded = copyBounded(zip, output, expanded, limit, cancelled)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun extractTarGz(archive: Path, root: Path, limit: Long, maximumEntries: Int, cancelled: () -> Boolean) {
        GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { input ->
            val header = ByteArray(512)
            var expanded = 0L
            var entries = 0
            var pendingLongName: String? = null
            while (true) {
                cancelled.throwIfCancelled()
                if (!input.readBlock(header)) break
                if (header.all { it == 0.toByte() }) break
                if (++entries > maximumEntries) throw ToolchainInstallFailure("ARCHIVE_ENTRY_LIMIT_EXCEEDED", "Archive entry count exceeds the catalog limit.")
                val storedChecksum = header.octal(148, 8)
                val calculated = header.indices.sumOf { index -> if (index in 148..155) 32L else (header[index].toInt() and 0xff).toLong() }
                if (storedChecksum != calculated) throw ToolchainInstallFailure("ARCHIVE_INVALID", "TAR header checksum mismatch.")
                val rawName = header.string(0, 100)
                val prefix = header.string(345, 155)
                val name = pendingLongName ?: listOf(prefix, rawName).filter(String::isNotEmpty).joinToString("/")
                pendingLongName = null
                val size = header.octal(124, 12)
                val type = header[156].toInt().toChar()
                val linkName = header.string(157, 100)
                when (type) {
                    '0', '\u0000' -> {
                        val destination = safeDestination(root, name)
                        safeParentDirectories(root, destination.parent)
                        Files.newOutputStream(destination).use { output ->
                            expanded = copyExactlyBounded(input, output, size, expanded, limit, cancelled)
                        }
                    }
                    '5' -> safeDestination(root, name).createDirectories()
                    '2' -> {
                        val destination = safeDestination(root, name)
                        val linkTarget = runCatching { Path.of(linkName) }.getOrNull()
                        if (linkName.isBlank() || linkName.any { it.code < 0x20 } || linkTarget == null || linkTarget.isAbsolute) {
                            throw ToolchainInstallFailure("ARCHIVE_LINK_INVALID", "Archive contains an unsafe symbolic link.")
                        }
                        safeParentDirectories(root, destination.parent)
                        val resolvedTarget = destination.parent.resolve(linkTarget).normalize()
                        if (!resolvedTarget.startsWith(root.normalize())) throw ToolchainInstallFailure("ARCHIVE_LINK_INVALID", "Archive link escapes extraction root.")
                        Files.createSymbolicLink(destination, linkTarget)
                        input.skipExactly(size)
                    }
                    'L' -> {
                        if (size > 4096) throw ToolchainInstallFailure("ARCHIVE_INVALID", "GNU long path exceeds limit.")
                        val bytes = input.readExactly(size.toInt())
                        pendingLongName = bytes.toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                    }
                    'x', 'g' -> input.skipExactly(size)
                    '1', '3', '4', '6', '7' -> throw ToolchainInstallFailure("ARCHIVE_ENTRY_UNSUPPORTED", "Archive contains a prohibited link or device entry.")
                    else -> throw ToolchainInstallFailure("ARCHIVE_ENTRY_UNSUPPORTED", "Archive contains an unsupported TAR entry type.")
                }
                val padding = (512 - size % 512) % 512
                input.skipExactly(padding)
            }
        }
    }

    private fun validateMetadata(artifact: ToolchainCatalogArtifact, root: Path) {
        val required = when (artifact.component) {
            ToolchainComponent.JDK -> listOf("bin/java", "release")
            ToolchainComponent.GRADLE -> listOf("bin/gradle", "lib")
            ToolchainComponent.ANDROID_COMMAND_LINE_TOOLS -> listOf("bin/sdkmanager", "source.properties")
            ToolchainComponent.ANDROID_PLATFORM -> listOf("android.jar", "source.properties")
            ToolchainComponent.ANDROID_BUILD_TOOLS -> listOf("aapt2", "apksigner", "source.properties")
            ToolchainComponent.ANDROID_NDK -> listOf("source.properties", "toolchains")
            ToolchainComponent.CMAKE -> listOf("bin/cmake")
        }
        if (required.any { !root.resolve(it).exists(LinkOption.NOFOLLOW_LINKS) }) {
            throw ToolchainInstallFailure("TOOLCHAIN_METADATA_INVALID", "Extracted ${artifact.artifactId} does not match its catalog component.")
        }
    }

    private fun createContentManifest(root: Path, cancelled: () -> Boolean): ContentManifest {
        val lines = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream.sorted().forEach { path ->
                cancelled.throwIfCancelled()
                if (path == root) return@forEach
                val relative = root.relativize(path).toString().replace('\\', '/')
                if (relative == CONTENT_MANIFEST_NAME) return@forEach
                when {
                    Files.isSymbolicLink(path) -> lines += "L\t$relative\t${Files.readSymbolicLink(path)}"
                    path.isDirectory(LinkOption.NOFOLLOW_LINKS) -> lines += "D\t$relative"
                    path.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> lines +=
                        "F\t$relative\t${Files.size(path)}\t${sha256(path)}\t${if (Files.isExecutable(path)) "X" else "-"}"
                    else -> throw ToolchainInstallFailure("TOOLCHAIN_CONTENT_INVALID", "Extracted content contains an unsupported file type.")
                }
            }
        }
        val text = lines.joinToString("\n", postfix = "\n")
        return ContentManifest(text, sha256(text.encodeToByteArray()))
    }

    private fun singleRootOrSelf(root: Path): Path {
        val entries = Files.list(root).use { it.toList() }
        return if (entries.size == 1 && entries.single().isDirectory(LinkOption.NOFOLLOW_LINKS)) entries.single() else root
    }

    private fun restoreExecutableEntries(component: ToolchainComponent, root: Path) {
        val executableRoots = when (component) {
            ToolchainComponent.JDK,
            ToolchainComponent.GRADLE,
            ToolchainComponent.ANDROID_COMMAND_LINE_TOOLS,
            ToolchainComponent.CMAKE,
            -> listOf(root.resolve("bin"))
            ToolchainComponent.ANDROID_BUILD_TOOLS -> BUILD_TOOLS_EXECUTABLES.map(root::resolve)
            ToolchainComponent.ANDROID_PLATFORM,
            ToolchainComponent.ANDROID_NDK,
            -> emptyList()
        }
        executableRoots.forEach { candidate ->
            if (candidate.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                Files.list(candidate).use { entries ->
                    entries.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.forEach(::makeExecutable)
                }
            } else if (candidate.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                makeExecutable(candidate)
            }
        }
    }

    private fun makeExecutable(path: Path) {
        val permissions = Files.getPosixFilePermissions(path).toMutableSet()
        permissions += setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        Files.setPosixFilePermissions(path, permissions)
    }

    private fun safeDestination(root: Path, raw: String): Path {
        val normalizedName = raw.removePrefix("./")
        if (normalizedName.isBlank() || normalizedName.startsWith('/') || normalizedName.any { it.code < 0x20 }) {
            throw ToolchainInstallFailure("ARCHIVE_PATH_INVALID", "Archive contains an invalid path.")
        }
        val destination = root.resolve(normalizedName).normalize()
        if (!destination.startsWith(root.normalize())) throw ToolchainInstallFailure("ARCHIVE_PATH_INVALID", "Archive path escapes extraction root.")
        return destination
    }

    private fun safeParentDirectories(root: Path, parent: Path) {
        var current = root
        root.relativize(parent).forEach { segment ->
            current = current.resolve(segment)
            if (current.exists(LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw ToolchainInstallFailure("ARCHIVE_PATH_INVALID", "Archive path traverses a symbolic link.")
            }
            current.createDirectories()
        }
    }

    private fun verifySha256(path: Path, expected: String) {
        if (sha256(path) != expected) throw ToolchainInstallFailure("ARCHIVE_SHA256_MISMATCH", "Archive SHA-256 does not match the trusted catalog.")
    }

    private fun requireTrustedDownloadUri(uri: URI) {
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.host !in TRUSTED_HOSTS) {
            throw ToolchainInstallFailure("DOWNLOAD_ORIGIN_REJECTED", "Download URL is outside the trusted catalog origin set.")
        }
    }

    private fun makeReadOnly(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                if (!Files.isSymbolicLink(path)) {
                    val permissions = Files.getPosixFilePermissions(path).toMutableSet()
                    permissions.remove(PosixFilePermission.OWNER_WRITE)
                    permissions.remove(PosixFilePermission.GROUP_WRITE)
                    permissions.remove(PosixFilePermission.OTHERS_WRITE)
                    Files.setPosixFilePermissions(path, permissions)
                }
            }
        }
    }

    private fun makeRootOwnerWritable(root: Path) {
        val permissions = Files.getPosixFilePermissions(root).toMutableSet()
        permissions.add(PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(root, permissions)
    }

    private fun makeOwnerWritable(root: Path) {
        if (!root.exists(LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.filter { !Files.isSymbolicLink(it) }.forEach { path ->
                val permissions = Files.getPosixFilePermissions(path).toMutableSet()
                permissions.add(PosixFilePermission.OWNER_WRITE)
                Files.setPosixFilePermissions(path, permissions)
            }
        }
    }

    private fun fsyncTree(root: Path) {
        Files.walk(root).use { stream -> stream.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.forEach(::fsyncFile) }
        Files.walk(root).use { stream -> stream.filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) }.sorted(Comparator.reverseOrder()).forEach(::fsyncDirectory) }
    }

    private fun fsyncFile(path: Path) = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    private fun fsyncDirectory(path: Path) = runCatching { java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) } }

    private fun deleteTree(root: Path) {
        if (!root.exists(LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun directoryBytes(root: Path): Long = Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }.mapToLong(Files::size).sum()
    }

    private fun sha256(path: Path): String = Files.newInputStream(path).use { input -> digest(input) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
    private fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        return digest.digest().toLowerHex()
    }

    private fun (() -> Boolean).throwIfCancelled() { if (invoke()) throw ToolchainCancelledException() }

    private data class ContentManifest(val text: String, val sha256: String)

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val CONTENT_MANIFEST_NAME = ".reprodroid-content-manifest"
        private const val SIGNATURE_TIMEOUT_NANOS = 30_000_000_000L
        private val BUILD_TOOLS_EXECUTABLES = listOf(
            "aapt", "aapt2", "aarch64-linux-android-ld", "aidl", "apksigner",
            "arm-linux-androideabi-ld", "bcc_compat", "d8", "dexdump",
            "i686-linux-android-ld", "lld", "llvm-rs-cc", "mipsel-linux-android-ld",
            "split-select", "x86_64-linux-android-ld", "zipalign",
        )
        private val TRUSTED_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "services.gradle.org", "downloads.gradle.org", "dl.google.com")
        private val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

internal data class InstalledToolchain(
    val artifactId: String,
    val component: ToolchainComponent,
    val version: String,
    val archiveSha256: String,
    val contentManifestSha256: String,
    val installedBytes: Long,
    val relativePath: String,
)

internal class ToolchainInstallFailure(val code: String, override val message: String) : RuntimeException(message)

private fun ByteArray.string(offset: Int, length: Int): String = copyOfRange(offset, offset + length)
    .takeWhile { it != 0.toByte() }.toByteArray().toString(StandardCharsets.UTF_8)

private fun ByteArray.octal(offset: Int, length: Int): Long {
    val value = string(offset, length).trim(' ', '\u0000')
    return if (value.isEmpty()) 0 else value.toLongOrNull(8)
        ?: throw ToolchainInstallFailure("ARCHIVE_INVALID", "TAR contains an invalid numeric field.")
}

private fun InputStream.readBlock(buffer: ByteArray): Boolean {
    var offset = 0
    while (offset < buffer.size) {
        val read = read(buffer, offset, buffer.size - offset)
        if (read < 0) {
            if (offset == 0) return false
            throw EOFException("Unexpected end of archive.")
        }
        offset += read
    }
    return true
}

private fun InputStream.readExactly(size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(result, offset, size - offset)
        if (read < 0) throw EOFException("Unexpected end of archive.")
        offset += read
    }
    return result
}

private fun InputStream.skipExactly(count: Long) {
    var remaining = count
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Unexpected end of archive.")
        remaining -= read
    }
}

private fun copyBounded(input: InputStream, output: java.io.OutputStream, initial: Long, limit: Long, cancelled: () -> Boolean): Long {
    var total = initial
    val buffer = ByteArray(128 * 1024)
    while (true) {
        if (cancelled()) throw ToolchainCancelledException()
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw ToolchainInstallFailure("ARCHIVE_EXPANDED_SIZE_EXCEEDED", "Expanded archive exceeds the catalog limit.")
        output.write(buffer, 0, read)
    }
    return total
}

private fun copyExactlyBounded(input: InputStream, output: java.io.OutputStream, count: Long, initial: Long, limit: Long, cancelled: () -> Boolean): Long {
    var remaining = count
    var total = initial
    val buffer = ByteArray(128 * 1024)
    while (remaining > 0) {
        if (cancelled()) throw ToolchainCancelledException()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Unexpected end of archive.")
        remaining -= read
        total += read
        if (total > limit) throw ToolchainInstallFailure("ARCHIVE_EXPANDED_SIZE_EXCEEDED", "Expanded archive exceeds the catalog limit.")
        output.write(buffer, 0, read)
    }
    return total
}
