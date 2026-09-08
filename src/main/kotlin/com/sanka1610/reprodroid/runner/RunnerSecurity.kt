package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

enum class TransportMode { DEVELOPMENT_HTTP, PAIRED_HTTPS }

internal data class TlsMaterial(
    val keyStore: KeyStore,
    val password: CharArray,
    val root: X509Certificate,
    val leaf: X509Certificate,
    val rootPin: String,
    val generationId: String,
)

/** Owner-only, atomic certificate storage. Private key bytes never enter SQLite. */
internal class LocalCertificateAuthority(
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val stateRoot = stateDirectory.toAbsolutePath().normalize()
    private val directory = stateRoot.resolve("security")
    private val activePath = directory.resolve("active-generation")

    fun initialize(
        runnerId: String,
        endpointHost: String,
        replaceRoot: Boolean = false,
        beforeActivate: (TlsMaterial) -> Unit = {},
    ): TlsMaterial = acquireConnectorLease().use {
        withPublicationLock { initializeLocked(runnerId, endpointHost, replaceRoot, beforeActivate) }
    }

    private fun initializeLocked(runnerId: String, endpointHost: String, replaceRoot: Boolean, beforeActivate: (TlsMaterial) -> Unit): TlsMaterial {
        check(isCanonical(runnerId)) { "RUNNER_IDENTITY_INVALID: runnerId is not canonical." }
        prepareDirectory()
        if (!replaceRoot && Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("SECURITY_ALREADY_INITIALIZED: use explicit root replacement to replace existing trust material.")
        }
        val rootKeys = ecKeyPair()
        val now = Instant.now(clock)
        val root = certificate(
            subject = X500Name("CN=RDR Root $runnerId"),
            issuer = X500Name("CN=RDR Root $runnerId"),
            publicKey = rootKeys.public,
            signingKey = rootKeys.private,
            issuerCertificate = null,
            notAfter = now.plus(5 * 365L, ChronoUnit.DAYS),
            root = true,
            endpointHost = null,
        )
        val leafKeys = ecKeyPair()
        val leaf = certificate(
            subject = X500Name("CN=ReproDroid Runner $runnerId"),
            issuer = X500Name(root.subjectX500Principal.name),
            publicKey = leafKeys.public,
            signingKey = rootKeys.private,
            issuerCertificate = root,
            notAfter = now.plus(30, ChronoUnit.DAYS),
            root = false,
            endpointHost = endpointHost,
        )
        val generation = UUID.randomUUID().toString()
        val tls = material(rootKeys.private, root, leafKeys.private, leaf, generation)
        publishGeneration(rootKeys.private, root, leafKeys.private, leaf, generation) { beforeActivate(tls) }
        return tls
    }

    fun loadOrRenew(
        endpointHost: String,
        beforeRenewalActivate: (TlsMaterial) -> Unit = {},
        validateExisting: (TlsMaterial) -> Unit = {},
        renew: Boolean = true,
    ): TlsMaterial = withPublicationLock(createDirectory = false) {
        loadOrRenewLocked(endpointHost, beforeRenewalActivate, validateExisting, renew, changeEndpoint = false)
    }

    fun changeEndpoint(endpointHost: String, beforeActivate: (TlsMaterial) -> Unit, validateExisting: (TlsMaterial) -> Unit): TlsMaterial =
        acquireConnectorLease().use {
            withPublicationLock(createDirectory = false) {
                loadOrRenewLocked(endpointHost, beforeActivate, validateExisting, renew = true, changeEndpoint = true)
            }
        }

    private fun loadOrRenewLocked(
        endpointHost: String, beforeRenewalActivate: (TlsMaterial) -> Unit, validateExisting: (TlsMaterial) -> Unit,
        renew: Boolean, changeEndpoint: Boolean,
    ): TlsMaterial {
        validateDirectory()
        var generation = readRegular(activePath, 64).toString(Charsets.US_ASCII)
        check(isCanonical(generation)) { "SECURITY_MATERIAL_INVALID: active generation is invalid." }
        val generationDirectory = directory.resolve(generation)
        validateOwnedDirectory(generationDirectory)
        val names = Files.list(generationDirectory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }
        check(names == setOf("root-key.pk8", "root-cert.der", "leaf-key.pk8", "leaf-cert.der")) {
            "SECURITY_MATERIAL_INVALID: active generation contains missing or unexpected files."
        }
        val rootKeyPath = generationDirectory.resolve("root-key.pk8")
        val rootCertPath = generationDirectory.resolve("root-cert.der")
        val leafKeyPath = generationDirectory.resolve("leaf-key.pk8")
        val leafCertPath = generationDirectory.resolve("leaf-cert.der")
        val rootKey = readPrivate(rootKeyPath)
        val root = readCertificate(rootCertPath)
        checkKeyPair(rootKey, root)
        var leafKey = readPrivate(leafKeyPath)
        var leaf = readCertificate(leafCertPath)
        checkKeyPair(leafKey, leaf)
        validateRoot(root)
        if (!leaf.issuerX500Principal.equals(root.subjectX500Principal) || !runCatching { leaf.verify(root.publicKey); true }.getOrDefault(false)) {
            throw IllegalStateException("SECURITY_MATERIAL_INVALID: leaf certificate is not issued by the configured root.")
        }
        checkP256(leaf)
        check(leaf.basicConstraints < 0 && leaf.keyUsage?.getOrNull(0) == true &&
            leaf.keyUsage?.withIndex()?.none { it.value && it.index != 0 } == true &&
            leaf.extendedKeyUsage == listOf(KeyPurposeId.id_kp_serverAuth.id) &&
            leaf.criticalExtensionOIDs.orEmpty() == setOf(Extension.basicConstraints.id, Extension.keyUsage.id)
        ) {
            "SECURITY_MATERIAL_INVALID: leaf usage profile is invalid."
        }
        val now = Instant.now(clock)
        if (!root.notAfter.toInstant().isAfter(now) || root.notBefore.toInstant().isAfter(now) || leaf.notBefore.toInstant().isAfter(now)) {
            throw IllegalStateException("SECURITY_MATERIAL_INVALID: root certificate is outside its validity period.")
        }
        check(changeEndpoint || leafHasSan(leaf, endpointHost)) { "SECURITY_MATERIAL_INVALID: leaf SAN does not match the configured endpoint." }
        check(root.publicKey != leaf.publicKey) { "SECURITY_MATERIAL_INVALID: root and leaf keys are not independent." }
        check(!leaf.notAfter.after(root.notAfter)) { "SECURITY_MATERIAL_INVALID: leaf outlives its root." }
        validateExisting(material(rootKey, root, leafKey, leaf, generation))
        if (renew && (changeEndpoint || Duration.between(now, leaf.notAfter.toInstant()) <= Duration.ofDays(7))) {
            check(root.notAfter.toInstant().isAfter(now.plus(30, ChronoUnit.DAYS))) {
                "SECURITY_ROOT_REPLACEMENT_REQUIRED: the root cannot issue another 30-day leaf."
            }
            generation = UUID.randomUUID().toString()
            leafKey = ecKeyPair().also { keys ->
                leaf = certificate(
                    subject = X500Name(leaf.subjectX500Principal.name),
                    issuer = X500Name(root.subjectX500Principal.name),
                    publicKey = keys.public,
                    signingKey = rootKey,
                    issuerCertificate = root,
                    notAfter = now.plus(30, ChronoUnit.DAYS),
                    root = false,
                    endpointHost = endpointHost,
                )
                val renewed = material(rootKey, root, keys.private, leaf, generation)
                publishGeneration(rootKey, root, keys.private, leaf, generation) { beforeRenewalActivate(renewed) }
            }.private
        }
        check(leaf.notAfter.toInstant().isAfter(now)) { "SECURITY_LEAF_EXPIRED: restart the paired Runner to renew its leaf." }
        return material(rootKey, root, leafKey, leaf, generation)
    }

    fun isInitialized(): Boolean = Files.isRegularFile(activePath, LinkOption.NOFOLLOW_LINKS)

    fun acquireConnectorLease(): java.io.Closeable {
        prepareDirectory()
        val path = directory.resolve("connector.lock")
        createLockFile(path)
        val channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) { channel.close(); error("SECURITY_CONNECTOR_RUNNING: stop the paired Runner before replacing its root or endpoint.") }
        return java.io.Closeable { try { lock.release() } finally { channel.close() } }
    }

    private fun createLockFile(lockPath: Path) {
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (POSIX_SUPPORTED) Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
                else Files.createFile(lockPath)
            } catch (_: java.nio.file.FileAlreadyExistsException) { /* Another process created the lock. */ }
        }
        check(!Files.isSymbolicLink(lockPath) && Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) { "SECURITY_MATERIAL_INVALID: security lock is unsafe." }
        validateFilePermissions(lockPath)
    }

    private fun <T> withPublicationLock(createDirectory: Boolean = true, action: () -> T): T {
        if (createDirectory) prepareDirectory() else validateDirectory()
        val lockPath = directory.resolve("publication.lock")
        createLockFile(lockPath)
        return FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = runCatching { channel.tryLock() }.getOrNull() ?: error("SECURITY_PUBLICATION_BUSY: another security command is running.")
            lock.use { action() }
        }
    }

    private fun material(rootKey: PrivateKey, root: X509Certificate, leafKey: PrivateKey, leaf: X509Certificate, generation: String): TlsMaterial {
        checkKeyPair(rootKey, root)
        checkKeyPair(leafKey, leaf)
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24)).toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("runner-leaf", leafKey, password, arrayOf(leaf, root))
        }
        return TlsMaterial(keyStore, password, root, leaf, rootPin(root), generation)
    }

    private fun certificate(
        subject: X500Name,
        issuer: X500Name,
        publicKey: java.security.PublicKey,
        signingKey: PrivateKey,
        issuerCertificate: X509Certificate?,
        notAfter: Instant,
        root: Boolean,
        endpointHost: String?,
    ): X509Certificate {
        val now = Instant.now(clock)
        val builder = X509v3CertificateBuilder(
            issuer,
            java.math.BigInteger(159, SecureRandom()).setBit(158),
            java.util.Date.from(now.minus(5, ChronoUnit.MINUTES)),
            java.util.Date.from(notAfter),
            subject,
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(publicKey.encoded),
        )
        val extensions = JcaX509ExtensionUtils()
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(publicKey))
        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extensions.createAuthorityKeyIdentifier(issuerCertificate?.publicKey ?: publicKey),
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(root))
        if (root) {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            val host = requireNotNull(endpointHost)
            val type = if (isIpLiteral(host)) GeneralName.iPAddress else GeneralName.dNSName
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(type, host)))
        }
        val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(signingKey))
        return JcaX509CertificateConverter().getCertificate(holder).also { it.checkValidity(java.util.Date.from(now)) }
    }

    private fun validateRoot(root: X509Certificate) {
        if (root.basicConstraints < 0 || !root.subjectX500Principal.equals(root.issuerX500Principal)) {
            throw IllegalStateException("SECURITY_MATERIAL_INVALID: configured root is not a self-signed CA.")
        }
        root.verify(root.publicKey)
        checkP256(root)
        check(root.keyUsage?.getOrNull(5) == true) { "SECURITY_MATERIAL_INVALID: root keyCertSign usage is missing." }
        check(root.keyUsage?.getOrNull(6) == true && root.keyUsage?.withIndex()?.none { it.value && it.index !in setOf(5, 6) } == true && root.extendedKeyUsage == null) {
            "SECURITY_MATERIAL_INVALID: root usage profile is invalid."
        }
        check(root.criticalExtensionOIDs.orEmpty() == setOf(Extension.basicConstraints.id, Extension.keyUsage.id)) {
            "SECURITY_MATERIAL_INVALID: root critical extensions are unsupported."
        }
    }

    private fun leafHasSan(certificate: X509Certificate, host: String): Boolean =
        certificate.subjectAlternativeNames?.singleOrNull()?.let { san ->
            val expectedType = if (isIpLiteral(host)) GeneralName.iPAddress else GeneralName.dNSName
            (san[0] as Number).toInt() == expectedType && if (expectedType == GeneralName.iPAddress) {
                runCatching { InetAddress.getByName(san[1].toString()).address.contentEquals(InetAddress.getByName(host).address) }.getOrDefault(false)
            } else san[1].toString().equals(host, ignoreCase = true)
        } == true

    private fun prepareDirectory() {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) validateDirectory() else {
            if (!Files.exists(stateRoot)) Files.createDirectories(stateRoot)
            validateStateAncestors()
            if (POSIX_SUPPORTED) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
            else { Files.createDirectory(directory); setPermissions(directory, true) }
            fsync(stateRoot)
        }
    }

    private fun validateDirectory() {
        validateStateAncestors()
        validateOwnedDirectory(directory)
    }

    private fun validateStateAncestors() {
        var current: Path? = stateRoot
        while (current != null) {
            check(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                "SECURITY_MATERIAL_INVALID: security ancestor is not a regular directory."
            }
            if (POSIX_SUPPORTED) {
                val permissions = Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS)
                // A shared sticky temporary root cannot replace a user-owned child. Other writable ancestors can.
                val sticky = (Files.getAttribute(current, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and 0x200 != 0
                check(permissions.none { it in GROUP_OR_OTHER_WRITE } || sticky && current != stateRoot) {
                    "SECURITY_MATERIAL_INVALID: security ancestor is group/world writable."
                }
            }
            current = current.parent
        }
        if (POSIX_SUPPORTED) check(Files.getOwner(stateRoot) == java.nio.file.FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))) {
            "SECURITY_MATERIAL_INVALID: state is not owned by the current user."
        }
    }

    private fun validateOwnedDirectory(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("SECURITY_MATERIAL_INVALID: security path is not a regular directory.")
        }
        try {
            val permissions = Files.getPosixFilePermissions(path)
            if (permissions != DIRECTORY_PERMISSIONS) throw IllegalStateException("SECURITY_MATERIAL_INVALID: security directory permissions are not 0700.")
            val owner = Files.getOwner(path)
            if (owner != Files.getOwner(stateRoot)) throw IllegalStateException("SECURITY_MATERIAL_INVALID: security ownership differs from state ownership.")
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX platforms rely on private process ownership and NOFOLLOW checks.
        }
    }

    private fun readPrivate(path: Path): PrivateKey {
        val bytes = readRegular(path, 16 * 1024)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes)).also {
            check(it.encoded.contentEquals(bytes)) { "SECURITY_MATERIAL_INVALID: private key encoding is not exact PKCS8." }
        }
    }

    private fun readCertificate(path: Path): X509Certificate {
        val bytes = readRegular(path, 64 * 1024)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        check(certificate.encoded.contentEquals(bytes)) { "SECURITY_MATERIAL_INVALID: certificate encoding is not exact DER." }
        return certificate
    }

    private fun readRegular(path: Path, max: Int): ByteArray {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("SECURITY_MATERIAL_INVALID: required security file is missing or unsafe.")
        }
        validateFilePermissions(path)
        val size = Files.size(path)
        require(size in 1..max.toLong()) { "SECURITY_MATERIAL_INVALID: security file size is invalid." }
        val bytes = Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            java.nio.channels.Channels.newInputStream(channel).use { input -> input.readNBytes(max + 1) }
        }
        require(bytes.isNotEmpty() && bytes.size <= max) { "SECURITY_MATERIAL_INVALID: security file size is invalid." }
        return bytes
    }

    private fun publish(target: Path, bytes: ByteArray, parent: Path) {
        val staging = parent.resolve(".${target.fileName}.${UUID.randomUUID()}.tmp")
        if (POSIX_SUPPORTED) Files.createFile(staging, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        else Files.createFile(staging)
        Files.newOutputStream(staging, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).use { output -> output.write(bytes); output.flush() }
        if (!POSIX_SUPPORTED) setPermissions(staging, false)
        FileChannel.open(staging, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        fsync(parent)
    }

    private fun publishGeneration(rootKey: PrivateKey, root: X509Certificate, leafKey: PrivateKey, leaf: X509Certificate, generation: String, beforeActivate: () -> Unit = {}) {
        val staging = directory.resolve(".$generation.staging")
        if (POSIX_SUPPORTED) Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        else { Files.createDirectory(staging); setPermissions(staging, true) }
        publish(staging.resolve("root-key.pk8"), rootKey.encoded, staging)
        publish(staging.resolve("root-cert.der"), root.encoded, staging)
        publish(staging.resolve("leaf-key.pk8"), leafKey.encoded, staging)
        publish(staging.resolve("leaf-cert.der"), leaf.encoded, staging)
        Files.move(staging, directory.resolve(generation), StandardCopyOption.ATOMIC_MOVE)
        fsync(directory)
        beforeActivate()
        publish(activePath, generation.toByteArray(Charsets.US_ASCII), directory)
    }

    private fun setPermissions(path: Path, directory: Boolean) {
        try {
            Files.setPosixFilePermissions(
                path,
                if (directory) setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                else setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs do not expose POSIX mode bits.
        }
    }

    private fun fsync(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !POSIX_SUPPORTED) return
        FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }

    private fun checkKeyPair(privateKey: PrivateKey, certificate: X509Certificate) {
        checkP256(certificate)
        val challenge = randomBytes(32)
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey); signature.update(challenge)
        val signed = signature.sign()
        signature.initVerify(certificate.publicKey); signature.update(challenge)
        check(signature.verify(signed)) { "SECURITY_MATERIAL_INVALID: private key does not match certificate." }
    }

    private fun checkP256(certificate: X509Certificate) {
        val key = certificate.publicKey as? java.security.interfaces.ECPublicKey
            ?: throw IllegalStateException("SECURITY_MATERIAL_INVALID: certificate key is not EC.")
        val expected = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        check(
            key.params.curve == expected.curve && key.params.generator == expected.generator &&
                key.params.order == expected.order && key.params.cofactor == expected.cofactor
        ) { "SECURITY_MATERIAL_INVALID: certificate key is not P-256." }
        check(certificate.sigAlgName.equals("SHA256withECDSA", ignoreCase = true)) { "SECURITY_MATERIAL_INVALID: certificate signature algorithm is unsupported." }
    }

    private fun validateFilePermissions(path: Path) {
        try {
            val permissions = Files.getPosixFilePermissions(path)
            if (permissions != FILE_PERMISSIONS) {
                throw IllegalStateException("SECURITY_MATERIAL_INVALID: security file permissions are not 0600.")
            }
            if (Files.getOwner(path) != Files.getOwner(stateRoot)) throw IllegalStateException("SECURITY_MATERIAL_INVALID: security file ownership differs from state ownership.")
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs do not expose POSIX mode bits.
        }
    }

    private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(java.security.spec.ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()

    companion object {
        private val GROUP_OR_OTHER_WRITE = setOf(
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
        private val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val DIRECTORY_PERMISSIONS = FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE
        private val POSIX_SUPPORTED = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        fun rootPin(certificate: X509Certificate): String = "sha256/" +
            Base64.getEncoder().encodeToString(sha256(certificate.publicKey.encoded))
        fun isIpLiteral(host: String): Boolean = host.contains(':') || host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
    }
}

@Serializable internal data class PairingIdentityResponse(val schemaVersion: Int = 1, val runnerId: String)
@Serializable internal data class PairingCreateRequest(
    val schemaVersion: Int,
    val runnerId: String,
    val invitationId: String,
    val invitationSecret: String,
    val deviceDisplayName: String,
    val tokenId: String,
    val tokenSha256: String,
    val continuationId: String,
    val continuationSha256: String,
) {
    override fun toString(): String = "PairingCreateRequest([REDACTED])"
}
@Serializable internal data class PairingStatusResponse(
    val schemaVersion: Int = 1,
    val runnerId: String,
    val requestId: String,
    val state: String,
    val expiresAt: String,
    val confirmationFingerprint: String,
    val principalId: String? = null,
)
@Serializable internal data class SelfRevocationResponse(
    val schemaVersion: Int = 1,
    val runnerId: String,
    val principalId: String,
    val state: String,
    val revokedAt: String,
)
@Serializable internal data class PairingInvitationPayload(
    val schemaVersion: Int = 1,
    val endpoint: String,
    val runnerId: String,
    val rootSpkiSha256: String,
    val invitationId: String,
    val invitationSecret: String,
    val expiresAt: String,
) {
    override fun toString(): String = "PairingInvitationPayload([REDACTED])"
}
@Serializable internal data class Principal(val id: String, val displayName: String, val state: String, val createdAt: String)
@Serializable internal data class PairingCliRequest(
    val requestId: String,
    val state: String,
    val deviceDisplayName: String,
    val endpoint: String,
    val createdAt: String,
    val expiresAt: String,
    val confirmationFingerprint: String,
)

internal class RunnerSecurityStore(
    private val stateDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val databasePath = stateDirectory.resolve("reprodroid-runner.sqlite3")
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; encodeDefaults = true }
    private val random = SecureRandom()
    private val rateLimiter = PairingRateLimiter(clock)

    fun requireMutationAllowed(source: String) {
        if (!rateLimiter.allow(source)) throw ApiException(HttpStatusCodeTooManyRequests, "PAIRING_UNAVAILABLE", "Pairing is temporarily unavailable.")
    }

    fun runnerId(): String = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT singleton,runner_id FROM runner_identity").use { row ->
                check(row.next()) { "RUNNER_IDENTITY_MISSING: initialize the Runner database first." }
                val value = row.getString(2)
                check(row.getInt(1) == 1 && isCanonical(value) && !row.next()) { "RUNNER_IDENTITY_INVALID: runnerId is not singular and canonical." }
                value
            }
        }
    }

    @Synchronized
    fun openInvitation(endpoint: String, pin: String): PairingInvitationPayload {
        val now = Instant.now(clock)
        val id = UUID.randomUUID().toString()
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32))
        val expiry = now.plus(5, ChronoUnit.MINUTES)
        connection().use { connection ->
            connection.autoCommit = false
            try {
            expire(connection, now)
            val count = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pairing_invitations WHERE state='OPEN'").use { it.next(); it.getInt(1) }
            }
            if (count >= 8) throw IllegalStateException("PAIRING_INVITATION_LIMIT: at most 8 invitations may be open.")
            connection.prepareStatement(
                "INSERT INTO pairing_invitations(invitation_id,secret_sha256,endpoint,state,invalid_attempts,expires_at,created_at) VALUES(?,?,?,'OPEN',0,?,?)",
            ).use { statement ->
                statement.setString(1, id); statement.setString(2, hexSha256(secret)); statement.setString(3, endpoint)
                statement.setString(4, expiry.toString()); statement.setString(5, now.toString()); statement.executeUpdate()
            }
            connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        return PairingInvitationPayload(endpoint = endpoint, runnerId = runnerId(), rootSpkiSha256 = pin, invitationId = id, invitationSecret = secret, expiresAt = expiry.toString())
    }

    @Synchronized
    fun createRequest(request: PairingCreateRequest, source: String): PairingStatusResponse {
        validatePairingCreate(request)
        val now = Instant.now(clock)
        connection().use { connection ->
            connection.autoCommit = false
            try {
                expire(connection, now)
                val row = connection.prepareStatement("SELECT * FROM pairing_invitations WHERE invitation_id=?").use { statement ->
                    statement.setString(1, request.invitationId)
                    statement.executeQuery().use { result -> if (result.next()) InvitationRow.from(result) else null }
                }
                val suppliedHash = hexSha256(request.invitationSecret)
                if (row == null || row.state !in setOf("OPEN", "CONSUMED") || !constantTime(row.secretSha256, suppliedHash)) {
                    if (row?.state == "OPEN") {
                        connection.prepareStatement(
                            "UPDATE pairing_invitations SET invalid_attempts=invalid_attempts+1,state=CASE WHEN invalid_attempts+1>=5 THEN 'LOCKED' ELSE state END WHERE invitation_id=?",
                        ).use { it.setString(1, request.invitationId); it.executeUpdate() }
                    }
                    connection.commit()
                    throw ApiException.unauthorizedPairing()
                }
                if (!Instant.parse(row.expiresAt).isAfter(now)) {
                    connection.commit(); throw ApiException.unauthorizedPairing()
                }
                val existing = loadPairingByInvitation(connection, request.invitationId)
                if (existing != null) {
                    val same = existing.tokenId == request.tokenId && constantTime(existing.tokenSha256, request.tokenSha256) &&
                        existing.continuationId == request.continuationId && constantTime(existing.continuationSha256, request.continuationSha256) &&
                        existing.deviceDisplayName == boundedLabel(request.deviceDisplayName)
                    connection.commit()
                    if (!same) throw ApiException.unauthorizedPairing()
                    return existing.response(runnerId())
                }
                if (row.state != "OPEN") { connection.commit(); throw ApiException.unauthorizedPairing() }
                val requestId = UUID.randomUUID().toString()
                val fingerprint = confirmationFingerprint(requestId, request.tokenSha256, request.continuationSha256)
                connection.prepareStatement("UPDATE pairing_invitations SET state='CONSUMED',consumed_at=? WHERE invitation_id=? AND state='OPEN'").use {
                    it.setString(1, now.toString()); it.setString(2, request.invitationId); check(it.executeUpdate() == 1)
                }
                connection.prepareStatement(
                    "INSERT INTO pairing_requests(request_id,invitation_id,device_display_name,token_id,token_sha256,continuation_id,continuation_sha256,confirmation_fingerprint,state,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,'PENDING_APPROVAL',?,?)",
                ).use {
                    it.setString(1, requestId); it.setString(2, request.invitationId); it.setString(3, boundedLabel(request.deviceDisplayName))
                    it.setString(4, request.tokenId); it.setString(5, request.tokenSha256); it.setString(6, request.continuationId)
                    it.setString(7, request.continuationSha256); it.setString(8, fingerprint); it.setString(9, row.expiresAt); it.setString(10, now.toString()); it.executeUpdate()
                }
                connection.commit()
                return PairingStatusResponse(runnerId = runnerId(), requestId = requestId, state = "PENDING_APPROVAL", expiresAt = row.expiresAt, confirmationFingerprint = fingerprint)
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
    }

    @Synchronized
    fun status(requestId: String, authorization: String?): PairingStatusResponse {
        val credential = parseContinuation(authorization)
        val now = Instant.now(clock)
        connection().use { connection ->
            expire(connection, now)
            val row = loadPairing(connection, requestId) ?: throw ApiException.unauthorizedPairing()
            if (credential.first != row.continuationId || !constantTime(row.continuationSha256, hexSha256(credential.second))) {
                throw ApiException.unauthorizedPairing()
            }
            return row.response(runnerId())
        }
    }

    @Synchronized
    fun cancel(requestId: String, authorization: String?) {
        val credential = parseContinuation(authorization)
        val now = Instant.now(clock)
        connection().use { connection ->
            expire(connection, now)
            val row = loadPairing(connection, requestId) ?: throw ApiException.unauthorizedPairing()
            if (credential.first != row.continuationId || !constantTime(row.continuationSha256, hexSha256(credential.second))) throw ApiException.unauthorizedPairing()
            if (row.state == "PENDING_APPROVAL") {
                connection.prepareStatement("UPDATE pairing_requests SET state='REJECTED',decided_at=? WHERE request_id=? AND state='PENDING_APPROVAL'").use {
                    it.setString(1, now.toString()); it.setString(2, requestId); it.executeUpdate()
                }
            }
        }
    }

    @Synchronized
    fun pending(): List<PairingStatusResponse> = connection().use { connection ->
        expire(connection, Instant.now(clock))
        connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM pairing_requests ORDER BY created_at").use { rows ->
            buildList { while (rows.next()) add(PairingRow.from(rows).response(runnerId())) }
        } }
    }

    @Synchronized
    fun pendingCli(): List<PairingCliRequest> = connection().use { connection ->
        expire(connection, Instant.now(clock))
        connection.createStatement().use { statement -> statement.executeQuery(
            "SELECT r.request_id,r.state,r.device_display_name,i.endpoint,r.created_at,r.expires_at,r.confirmation_fingerprint FROM pairing_requests r JOIN pairing_invitations i ON i.invitation_id=r.invitation_id ORDER BY r.created_at",
        ).use { rows -> buildList {
            while (rows.next()) add(PairingCliRequest(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7)))
        } } }
    }

    @Synchronized
    fun decide(requestId: String, approve: Boolean): PairingStatusResponse {
        val now = Instant.now(clock)
        connection().use { connection ->
            connection.autoCommit = false
            try {
                expire(connection, now)
                val row = loadPairing(connection, requestId) ?: throw IllegalArgumentException("PAIRING_REQUEST_NOT_FOUND")
                if (row.state != "PENDING_APPROVAL") { connection.commit(); return row.response(runnerId()) }
                check(Instant.parse(row.expiresAt).isAfter(now)) { "PAIRING_REQUEST_EXPIRED" }
                if (!approve) {
                    connection.prepareStatement("UPDATE pairing_requests SET state='REJECTED',decided_at=? WHERE request_id=?").use {
                        it.setString(1, now.toString()); it.setString(2, requestId); it.executeUpdate()
                    }
                } else {
                    val principalId = UUID.randomUUID().toString()
                    connection.prepareStatement("INSERT INTO principals(principal_id,kind,display_name,state,created_at) VALUES(?,'PAIRED',?,'ACTIVE',?)").use {
                        it.setString(1, principalId); it.setString(2, row.deviceDisplayName); it.setString(3, now.toString()); it.executeUpdate()
                    }
                    connection.prepareStatement("INSERT INTO credentials(token_id,token_sha256,principal_id,created_at,state) VALUES(?,?,?,?,'ACTIVE')").use {
                        it.setString(1, row.tokenId); it.setString(2, row.tokenSha256); it.setString(3, principalId); it.setString(4, now.toString()); it.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE pairing_requests SET state='APPROVED',principal_id=?,decided_at=? WHERE request_id=? AND state='PENDING_APPROVAL'").use {
                        it.setString(1, principalId); it.setString(2, now.toString()); it.setString(3, requestId); check(it.executeUpdate() == 1)
                    }
                    connection.prepareStatement("INSERT INTO security_audit_events(event_id,event_kind,subject_id,detail_json,created_at) VALUES(?,'PAIRING_APPROVED',?,'{}',?)").use {
                        it.setString(1, UUID.randomUUID().toString()); it.setString(2, principalId); it.setString(3, now.toString()); it.executeUpdate()
                    }
                }
                connection.commit()
                return requireNotNull(loadPairingFresh(requestId)).response(runnerId())
            } catch (failure: Throwable) { runCatching { connection.rollback() }; throw failure }
        }
    }

    @Synchronized
    fun authenticate(header: String?): String {
        val parsed = parseBearer(header) ?: throw ApiException.unauthorized()
        val hash = hexSha256(parsed.full)
        connection().use { connection ->
            val principal = connection.prepareStatement(
                "SELECT c.token_sha256,c.principal_id FROM credentials c JOIN principals p ON p.principal_id=c.principal_id WHERE c.token_id=? AND c.state='ACTIVE' AND p.state='ACTIVE'",
            ).use { statement ->
                statement.setString(1, parsed.tokenId)
                statement.executeQuery().use { row -> if (row.next() && constantTime(row.getString(1), hash)) row.getString(2) else null }
            } ?: throw ApiException.unauthorized()
            val rounded = Instant.now(clock).truncatedTo(ChronoUnit.HOURS).toString()
            connection.prepareStatement("UPDATE credentials SET last_used_at=? WHERE token_id=?").use { it.setString(1, rounded); it.setString(2, parsed.tokenId); it.executeUpdate() }
            return principal
        }
    }

    @Synchronized
    fun selfRevoke(principalId: String): SelfRevocationResponse = revoke(principalId, "SELF", "Self revocation")

    @Synchronized
    fun revoke(principalId: String, actor: String = "LOCAL_CLI", reason: String = "Local operator revocation"): SelfRevocationResponse {
        require(principalId != SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) { "The development principal cannot be revoked." }
        val now = Instant.now(clock).toString()
        connection().use { connection ->
            connection.autoCommit = false
            try {
                val updated = connection.prepareStatement("UPDATE principals SET state='REVOKED',revoked_at=COALESCE(revoked_at,?) WHERE principal_id=? AND kind='PAIRED'").use {
                    it.setString(1, now); it.setString(2, principalId); it.executeUpdate()
                }
                check(updated == 1) { "PRINCIPAL_NOT_FOUND" }
                connection.prepareStatement("UPDATE credentials SET state='REVOKED',revoked_at=COALESCE(revoked_at,?) WHERE principal_id=?").use {
                    it.setString(1, now); it.setString(2, principalId); it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO revocation_events(event_id,principal_id,actor_kind,reason,created_at) VALUES(?,?,?,?,?)").use {
                    it.setString(1, UUID.randomUUID().toString()); it.setString(2, principalId); it.setString(3, actor); it.setString(4, reason.take(256)); it.setString(5, now); it.executeUpdate()
                }
                connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
        return SelfRevocationResponse(runnerId = runnerId(), principalId = principalId, state = "REVOKED", revokedAt = now)
    }

    fun principals(): List<Principal> = connection().use { connection ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT principal_id,display_name,state,created_at FROM principals ORDER BY created_at").use { rows ->
            buildList { while (rows.next()) add(Principal(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))) }
        } }
    }

    fun requireUninitializedCertificates() {
        connection().use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM security_certificates").use { row ->
                check(row.next() && row.getInt(1) == 0) { "SECURITY_ALREADY_INITIALIZED: missing material requires explicit root replacement." }
            }
        } }
    }

    @Synchronized
    fun recordCertificates(material: TlsMaterial, replaceRoot: Boolean = false) {
        val now = Instant.now(clock).toString()
        requireCertificateIdentity(material)
        connection().use { connection ->
            connection.autoCommit = false
            try {
                if (replaceRoot) replaceRootRevocation(connection)
                connection.createStatement().use { it.executeUpdate("UPDATE security_certificates SET state='RETIRED' WHERE state='ACTIVE'") }
                listOf("ROOT" to material.root, "LEAF" to material.leaf).forEach { (kind, certificate) ->
                    val certificateId = UUID.nameUUIDFromBytes("${material.generationId}:$kind".toByteArray(Charsets.US_ASCII)).toString()
                    val inserted = connection.prepareStatement(
                        "INSERT OR IGNORE INTO security_certificates(certificate_id,generation_id,kind,certificate_sha256,spki_sha256,serial_hex,not_before,not_after,relative_path,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,'ACTIVE',?)",
                    ).use {
                        it.setString(1, certificateId); it.setString(2, material.generationId); it.setString(3, kind)
                        it.setString(4, sha256(certificate.encoded).hexSecurity()); it.setString(5, sha256(certificate.publicKey.encoded).hexSecurity())
                        it.setString(6, certificate.serialNumber.toString(16)); it.setString(7, certificate.notBefore.toInstant().toString()); it.setString(8, certificate.notAfter.toInstant().toString())
                        it.setString(9, "security/${material.generationId}/${kind.lowercase()}-cert.der"); it.setString(10, now); it.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE security_certificates SET state='ACTIVE' WHERE certificate_id=?").use { it.setString(1, certificateId); it.executeUpdate() }
                    if (kind == "LEAF" && inserted == 1) connection.prepareStatement(
                        "INSERT INTO security_audit_events(event_id,event_kind,subject_id,detail_json,created_at) VALUES(?,'LEAF_PUBLISHED',?,'{}',?)",
                    ).use { it.setString(1, UUID.randomUUID().toString()); it.setString(2, certificateId); it.setString(3, now); it.executeUpdate() }
                }
                connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
    }

    fun verifyCertificates(material: TlsMaterial) {
        requireCertificateIdentity(material)
        val expected = listOf("ROOT" to material.root, "LEAF" to material.leaf).associate { (kind, certificate) ->
            kind to listOf(material.generationId, sha256(certificate.encoded).hexSecurity(), sha256(certificate.publicKey.encoded).hexSecurity(),
                certificate.serialNumber.toString(16), certificate.notBefore.toInstant().toString(), certificate.notAfter.toInstant().toString(),
                "security/${material.generationId}/${kind.lowercase()}-cert.der")
        }
        connection().use { connection ->
            val actual = connection.createStatement().use { statement -> statement.executeQuery(
                "SELECT kind,generation_id,certificate_sha256,spki_sha256,serial_hex,not_before,not_after,relative_path FROM security_certificates WHERE state='ACTIVE'",
            ).use { rows -> buildMap {
                while (rows.next()) {
                    val kind = rows.getString(1)
                    check(kind !in this) { "SECURITY_METADATA_MISMATCH: duplicate active certificate kind." }
                    put(kind, (2..8).map(rows::getString))
                }
            } } }
            check(actual == expected) { "SECURITY_METADATA_MISMATCH: active certificate files do not match durable metadata." }
        }
    }

    private fun requireCertificateIdentity(material: TlsMaterial) {
        val id = runnerId()
        check(isCanonical(material.generationId) &&
            material.root.subjectX500Principal == javax.security.auth.x500.X500Principal("CN=RDR Root $id") &&
            material.leaf.subjectX500Principal == javax.security.auth.x500.X500Principal("CN=ReproDroid Runner $id")) {
            "SECURITY_IDENTITY_MISMATCH: certificate identity does not match the persistent Runner."
        }
    }

    @Synchronized
    fun replaceRootRevocation() {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                replaceRootRevocation(connection)
                connection.commit()
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
    }

    private fun replaceRootRevocation(connection: Connection) {
                val now = Instant.now(clock).toString()
                connection.prepareStatement("UPDATE pairing_invitations SET state='LOCKED' WHERE state='OPEN'").use { it.executeUpdate() }
                connection.prepareStatement("UPDATE pairing_requests SET state='FAILED',decided_at=? WHERE state='PENDING_APPROVAL'").use { it.setString(1, now); it.executeUpdate() }
                val ids = connection.createStatement().use { statement -> statement.executeQuery("SELECT principal_id FROM principals WHERE kind='PAIRED' AND state='ACTIVE'").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } } }
                connection.prepareStatement("UPDATE principals SET state='REVOKED',revoked_at=? WHERE kind='PAIRED' AND state='ACTIVE'").use { it.setString(1, now); it.executeUpdate() }
                connection.prepareStatement("UPDATE credentials SET state='REVOKED',revoked_at=? WHERE state='ACTIVE'").use { it.setString(1, now); it.executeUpdate() }
                connection.prepareStatement("INSERT INTO revocation_events(event_id,principal_id,actor_kind,reason,created_at) VALUES(?,?,'ROOT_REPLACEMENT','Local root replacement',?)").use { statement ->
                    ids.forEach { id -> statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, id); statement.setString(3, now); statement.addBatch() }
                    statement.executeBatch()
                }
    }

    @Synchronized
    fun previewAdoption(source: String, target: String): AdoptionPreview {
        require(source != target)
        val now = Instant.now(clock)
        connection().use { connection ->
            connection.autoCommit = false
            try {
            requireAdoptionSchema(connection)
            requirePrincipalSourceAndTarget(connection, source, target)
            val counts = adoptionCounts(connection, source)
            val blockers = adoptionBlockers(connection, source, target)
            val snapshot = adoptionPairSnapshot(connection, source, target)
            val preview = AdoptionPreview(UUID.randomUUID().toString(), source, target, counts, now.plus(10, ChronoUnit.MINUTES).toString(), blockers)
            connection.prepareStatement("INSERT INTO ownership_adoption_previews(preview_id,source_principal_id,target_principal_id,snapshot_sha256,counts_json,state,expires_at,created_at) VALUES(?,?,?,?,?,'OPEN',?,?)").use {
                it.setString(1, preview.previewId); it.setString(2, source); it.setString(3, target); it.setString(4, snapshot);
                it.setString(5, json.encodeToString(counts)); it.setString(6, preview.expiresAt); it.setString(7, now.toString()); it.executeUpdate()
            }
            connection.commit()
            return preview
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
    }

    @Synchronized
    fun executeAdoption(previewId: String): AdoptionPreview {
        val now = Instant.now(clock)
        connection().use { connection ->
            connection.autoCommit = false
            try {
                requireAdoptionSchema(connection)
                val row = connection.prepareStatement("SELECT * FROM ownership_adoption_previews WHERE preview_id=?").use { statement ->
                    statement.setString(1, previewId); statement.executeQuery().use { result ->
                        if (!result.next()) null else AdoptionDbRow(result.getString("source_principal_id"), result.getString("target_principal_id"), result.getString("snapshot_sha256"), result.getString("state"), result.getString("expires_at"))
                    }
                } ?: error("ADOPTION_PREVIEW_NOT_FOUND")
                check(row.state == "OPEN" && Instant.parse(row.expiresAt).isAfter(now)) { "ADOPTION_PREVIEW_EXPIRED_OR_USED" }
                requirePrincipalSourceAndTarget(connection, row.source, row.target)
                val counts = adoptionCounts(connection, row.source)
                check(adoptionBlockers(connection, row.source, row.target).isEmpty()) { "ADOPTION_CONFLICT" }
                check(constantTime(row.snapshot, adoptionPairSnapshot(connection, row.source, row.target))) { "ADOPTION_PREVIEW_STALE" }
                ADOPTION_TABLES.forEach { (table, kind) ->
                    connection.prepareStatement("UPDATE $table SET principal_id=? WHERE principal_id=?").use {
                        it.setString(1, row.target); it.setString(2, row.source)
                        check(it.executeUpdate() == counts.getValue(kind)) { "ADOPTION_ROW_COUNT_CHANGED" }
                    }
                }
                connection.prepareStatement("UPDATE ownership_adoption_previews SET state='EXECUTED',executed_at=? WHERE preview_id=? AND state='OPEN'").use {
                    it.setString(1, now.toString()); it.setString(2, previewId); check(it.executeUpdate() == 1)
                }
                connection.prepareStatement("INSERT INTO ownership_adoption_audit(audit_id,preview_id,source_principal_id,target_principal_id,counts_json,created_at) VALUES(?,?,?,?,?,?)").use {
                    it.setString(1, UUID.randomUUID().toString()); it.setString(2, previewId); it.setString(3, row.source); it.setString(4, row.target)
                    it.setString(5, json.encodeToString(counts)); it.setString(6, now.toString()); it.executeUpdate()
                }
                connection.commit()
                return AdoptionPreview(previewId, row.source, row.target, counts, row.expiresAt, emptyList())
            } catch (failure: Throwable) { connection.rollback(); throw failure }
        }
    }

    private fun requirePrincipalSourceAndTarget(connection: Connection, source: String, target: String) {
        fun state(id: String): String? = connection.prepareStatement("SELECT state FROM principals WHERE principal_id=?").use {
            it.setString(1, id); it.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
        }
        check(state(source) != null && (source == SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL || state(source) == "REVOKED")) { "ADOPTION_SOURCE_INVALID" }
        check(state(target) == "ACTIVE" && target != SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) { "ADOPTION_TARGET_INVALID" }
    }

    private fun adoptionCounts(connection: Connection, source: String): Map<String, Int> = ADOPTION_TABLES.associate { (table, kind) ->
        kind to connection.prepareStatement("SELECT count(*) FROM $table WHERE principal_id=?").use {
            it.setString(1, source); it.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
    }

    private fun adoptionSnapshot(connection: Connection, source: String): String {
        // Length framing distinguishes delimiters, SQL NULL, empty strings, and arbitrary stored BLOBs.
        // Streaming keeps a large retained history from becoming one unbounded in-memory string.
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(bytes: ByteArray?) {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        ADOPTION_SNAPSHOT_QUERIES.forEach { (kind, query) ->
            field(kind.toByteArray(Charsets.UTF_8))
            connection.prepareStatement(query).use { statement ->
                statement.setString(1, source)
                statement.executeQuery().use { result ->
                    val columns = result.metaData.columnCount
                    (1..columns).forEach { field(result.metaData.getColumnName(it).toByteArray(Charsets.UTF_8)) }
                    while (result.next()) {
                        digest.update(1.toByte())
                        (1..columns).forEach { field(result.getBytes(it)) }
                    }
                    digest.update(0.toByte())
                }
            }
        }
        return digest.digest().hexSecurity()
    }

    private fun adoptionPairSnapshot(connection: Connection, source: String, target: String): String =
        hexSha256(adoptionSnapshot(connection, source) + ":" + adoptionSnapshot(connection, target))

    private fun requireAdoptionSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { check(it.next() && it.getInt(1) == SQLiteJobStore.SCHEMA_VERSION) { "ADOPTION_UNKNOWN_SCHEMA" } }
            statement.executeQuery("PRAGMA foreign_key_check").use { check(!it.next()) { "ADOPTION_MISSING_RESOURCE" } }
            val actualTables = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
            check(actualTables == ADOPTION_KNOWN_TABLES) { "ADOPTION_UNKNOWN_SCHEMA" }
        }
        val principalTables = ADOPTION_KNOWN_TABLES.filter { table ->
            connection.createStatement().use { statement -> statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                var found = false
                while (rows.next()) if (rows.getString("name") == "principal_id") found = true
                found
            } }
        }.toSet()
        check(principalTables == ADOPTION_TABLES.map { it.first }.toSet() + setOf("principals", "credentials", "pairing_requests", "revocation_events")) {
            "ADOPTION_UNKNOWN_OWNERSHIP_SCHEMA"
        }
    }

    private fun adoptionBlockers(connection: Connection, source: String, target: String): List<String> = buildList {
        fun count(sql: String): Int = connection.prepareStatement(sql).use { statement ->
            statement.setString(1, source)
            statement.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
        if (count("SELECT count(*) FROM jobs WHERE principal_id=? AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')") > 0) add("ACTIVE_JOB")
        if (count("SELECT count(*) FROM jobs WHERE principal_id=? AND state='AWAITING_SCAN_REVIEW'") > 0) add("AWAITING_REVIEW")
        if (count("SELECT count(*) FROM jobs WHERE principal_id=? AND sandbox_cleanup_status IS NOT NULL AND sandbox_cleanup_status NOT IN ('COMPLETE','NOT_REQUIRED')") > 0) add("SANDBOX_CLEANUP_PENDING")
        if (count("SELECT count(*) FROM sandbox_resources s JOIN jobs j ON j.job_id=s.job_id WHERE j.principal_id=? AND s.removed<>1") > 0) add("SANDBOX_RESOURCE_UNRESOLVED")
        if (count("SELECT count(*) FROM source_scan_results s JOIN jobs j ON j.job_id=s.job_id WHERE j.principal_id=? AND s.schema_version<>1") > 0) add("UNKNOWN_SCAN_SCHEMA")
        if (count("SELECT count(*) FROM cleanup_runs WHERE principal_id=? AND state NOT IN ('COMPLETE','PARTIAL','REJECTED')") > 0) add("ACTIVE_OR_UNRESOLVED_CLEANUP")
        if (count("SELECT count(*) FROM operations WHERE principal_id=? AND state NOT IN ('COMPLETED','REJECTED')") > 0) add("ACTIVE_OR_UNRESOLVED_OPERATION")
        if (count("SELECT count(*) FROM storage_reservations WHERE principal_id=? AND state NOT IN ('RELEASED','REJECTED')") > 0) add("ACTIVE_OR_UNRESOLVED_RESERVATION")
        if (count("SELECT count(*) FROM retention_holds WHERE principal_id=? AND state NOT IN ('ACTIVE','RELEASED')") > 0) add("UNKNOWN_HOLD_STATE")
        if (count("SELECT count(*) FROM toolchain_installations WHERE principal_id=? AND state NOT IN ('INSTALLED','CANCELLED','FAILED')") > 0) add("ACTIVE_OR_UNRESOLVED_TOOLCHAIN")
        if (count("SELECT count(*) FROM toolchain_installation_items i JOIN toolchain_installations t ON t.installation_id=i.installation_id WHERE t.principal_id=? AND i.state='RECONCILIATION_REQUIRED'") > 0) add("UNRESOLVED_TOOLCHAIN_ITEM")
        val activeRemovalPreview = connection.prepareStatement("SELECT expires_at FROM toolchain_removal_previews WHERE principal_id=?").use { statement ->
            statement.setString(1, source)
            statement.executeQuery().use { rows ->
                var active = false
                while (rows.next()) if (Instant.parse(rows.getString(1)).isAfter(Instant.now(clock))) active = true
                active
            }
        }
        if (activeRemovalPreview) add("ACTIVE_REMOVAL_PREVIEW")
        if (count("SELECT count(*) FROM generic_comparisons c JOIN jobs a ON a.job_id=c.build_a_job_id JOIN jobs b ON b.job_id=c.build_b_job_id WHERE c.principal_id=? AND (a.principal_id<>c.principal_id OR b.principal_id<>c.principal_id)") > 0) add("RESOURCE_OWNER_CONFLICT")
        if (count("SELECT count(*) FROM retention_holds h LEFT JOIN jobs j ON h.resource_kind='JOB' AND h.resource_id=j.job_id LEFT JOIN artifacts a ON h.resource_kind='ARTIFACT' AND h.resource_id=a.artifact_id LEFT JOIN jobs aj ON aj.job_id=a.job_id WHERE h.principal_id=? AND (CASE h.resource_kind WHEN 'JOB' THEN j.principal_id WHEN 'ARTIFACT' THEN aj.principal_id END IS NULL OR COALESCE(j.principal_id,aj.principal_id)<>h.principal_id)") > 0) add("MISSING_OR_FOREIGN_HELD_RESOURCE")
        if (count("SELECT count(*) FROM operations WHERE principal_id=? AND (contract_version<>1 OR contract_id NOT IN ('storage-retention','toolchain-install','generic-build','apk-comparison'))") > 0) add("UNKNOWN_RESOURCE_CONTRACT")
        if (count("""SELECT count(*) FROM operations WHERE principal_id=? AND NOT (
            (contract_id='storage-retention' AND operation_kind IN ('retention-hold-create','retention-hold-release','storage-reservation-create','storage-reservation-release','cleanup-preview-create','cleanup-execute')) OR
            (contract_id='toolchain-install' AND operation_kind IN ('toolchain-install','toolchain-remove')) OR
            (contract_id='generic-build' AND operation_kind='GENERIC_BUILD_CREATE') OR
            (contract_id='apk-comparison' AND operation_kind IN ('GENERIC_COMPARISON_CREATE','GENERIC_RESOURCE_RETRY'))
        )""") > 0) add("UNKNOWN_OPERATION_KIND")
        fun conflicts(sql: String): Int = connection.prepareStatement(sql).use { statement ->
            statement.setString(1, target); statement.setString(2, source)
            statement.executeQuery().use { row -> row.next(); row.getInt(1) }
        }
        if (conflicts("SELECT count(*) FROM operations s JOIN operations t ON t.principal_id=? AND t.operation_kind=s.operation_kind AND t.idempotency_key=s.idempotency_key WHERE s.principal_id=?") > 0) add("IDEMPOTENCY_CONFLICT")
        if (conflicts("SELECT count(*) FROM toolchain_license_acceptances s JOIN toolchain_license_acceptances t ON t.principal_id=? AND t.runner_id=s.runner_id AND t.license_id=s.license_id AND t.license_text_sha256=s.license_text_sha256 WHERE s.principal_id=?") > 0) add("LICENSE_CONFLICT")
        if (conflicts("SELECT count(*) FROM toolchain_removal_previews s JOIN toolchain_removal_previews t ON t.principal_id=? AND t.idempotency_key=s.idempotency_key WHERE s.principal_id=?") > 0) add("REMOVAL_IDEMPOTENCY_CONFLICT")
        if (conflicts("SELECT count(*) FROM retention_holds s JOIN retention_holds t ON t.principal_id=? AND s.state='ACTIVE' AND t.state='ACTIVE' AND s.resource_kind=t.resource_kind AND s.resource_id=t.resource_id AND s.reason=t.reason AND s.client_reference_type=t.client_reference_type AND s.client_reference_id=t.client_reference_id WHERE s.principal_id=?") > 0) add("ACTIVE_HOLD_CONFLICT")
    }.sorted()

    private fun validatePairingCreate(request: PairingCreateRequest) {
        if (request.schemaVersion != 1 || request.runnerId != runnerId()) throw ApiException.unauthorizedPairing()
        requireCanonical(request.invitationId); requireCanonical(request.tokenId); requireCanonical(request.continuationId)
        if (!isCanonicalSecret(request.invitationSecret) || !request.tokenSha256.matches(SHA_PATTERN) || !request.continuationSha256.matches(SHA_PATTERN)) {
            throw ApiException.unauthorizedPairing()
        }
        if (request.deviceDisplayName.isBlank() || request.deviceDisplayName.toByteArray().size > 128 ||
            request.deviceDisplayName.any { it.isISOControl() || it.code in 0x7f..0x9f }
        ) throw ApiException.badRequest("INVALID_REQUEST", "deviceDisplayName is invalid.")
    }

    private fun parseContinuation(header: String?): Pair<String, String> {
        val raw = header?.takeIf { it.startsWith("ReproDroid-Continuation ") }?.removePrefix("ReproDroid-Continuation ") ?: throw ApiException.unauthorizedPairing()
        val parts = raw.split('.', limit = 2)
        if (raw.length > 80 || parts.size != 2 || !isCanonical(parts[0]) || !isCanonicalSecret(parts[1])) throw ApiException.unauthorizedPairing()
        return parts[0] to parts[1]
    }

    private fun parseBearer(header: String?): ParsedBearer? {
        val full = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return null
        if (full.length > 256 || !full.all { it.code in 0x21..0x7e }) return null
        val parts = full.split('.')
        if (parts.size != 3 || parts[0] != "rdb1" || !isCanonical(parts[1]) || !isCanonicalSecret(parts[2])) return null
        return ParsedBearer(parts[1], full)
    }

    private fun expire(connection: Connection, now: Instant) {
        fun expireTable(table: String, key: String, openState: String, decisionTime: Boolean = false) {
            val expired = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT $key,expires_at FROM $table WHERE state='$openState'").use { rows -> buildList {
                    while (rows.next()) if (!Instant.parse(rows.getString(2)).isAfter(now)) add(rows.getString(1))
                } }
            }
            val decision = if (decisionTime) ",decided_at=?" else ""
            connection.prepareStatement("UPDATE $table SET state='EXPIRED'$decision WHERE $key=? AND state='$openState'").use { statement ->
                expired.forEach { id ->
                    if (decisionTime) statement.setString(1, now.toString())
                    statement.setString(if (decisionTime) 2 else 1, id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        // RFC3339 strings have variable fractional precision and are not lexicographically ordered.
        expireTable("pairing_invitations", "invitation_id", "OPEN")
        expireTable("pairing_requests", "request_id", "PENDING_APPROVAL", decisionTime = true)
        expireTable("ownership_adoption_previews", "preview_id", "OPEN")
    }

    private fun loadPairingFresh(requestId: String): PairingRow? = connection().use { loadPairing(it, requestId) }
    private fun loadPairingByInvitation(connection: Connection, invitationId: String): PairingRow? = connection.prepareStatement("SELECT * FROM pairing_requests WHERE invitation_id=?").use {
        it.setString(1, invitationId); it.executeQuery().use { row -> if (row.next()) PairingRow.from(row) else null }
    }
    private fun loadPairing(connection: Connection, requestId: String): PairingRow? = connection.prepareStatement("SELECT * FROM pairing_requests WHERE request_id=?").use {
        it.setString(1, requestId); it.executeQuery().use { row -> if (row.next()) PairingRow.from(row) else null }
    }
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath", java.util.Properties().apply {
        // Security transitions also run in separate local CLI processes. JVM synchronization alone is insufficient.
        setProperty("transaction_mode", "IMMEDIATE")
    }).apply { createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") } }
    private fun boundedLabel(value: String) = value.trim().take(64)
    private fun requireCanonical(value: String) { if (!isCanonical(value)) throw ApiException.unauthorizedPairing() }

    private data class ParsedBearer(val tokenId: String, val full: String)
    private data class InvitationRow(val secretSha256: String, val state: String, val expiresAt: String) {
        companion object { fun from(row: java.sql.ResultSet) = InvitationRow(row.getString("secret_sha256"), row.getString("state"), row.getString("expires_at")) }
    }
    private data class PairingRow(
        val requestId: String, val deviceDisplayName: String, val tokenId: String, val tokenSha256: String,
        val continuationId: String, val continuationSha256: String, val fingerprint: String, val principalId: String?, val state: String, val expiresAt: String,
    ) {
        fun response(runnerId: String) = PairingStatusResponse(runnerId = runnerId, requestId = requestId, state = state, expiresAt = expiresAt, confirmationFingerprint = fingerprint, principalId = principalId.takeIf { state == "APPROVED" })
        companion object { fun from(r: java.sql.ResultSet) = PairingRow(r.getString("request_id"), r.getString("device_display_name"), r.getString("token_id"), r.getString("token_sha256"), r.getString("continuation_id"), r.getString("continuation_sha256"), r.getString("confirmation_fingerprint"), r.getString("principal_id"), r.getString("state"), r.getString("expires_at")) }
    }
    private data class AdoptionDbRow(val source: String, val target: String, val snapshot: String, val state: String, val expiresAt: String)

    companion object {
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ADOPTION_TABLES = listOf(
            "jobs" to "jobs", "operations" to "operations", "retention_holds" to "retentionHolds",
            "storage_reservations" to "storageReservations", "cleanup_runs" to "cleanupRuns",
            "toolchain_installations" to "toolchainInstallations", "toolchain_license_acceptances" to "toolchainLicenseAcceptances",
            "toolchain_removal_previews" to "toolchainRemovalPreviews", "generic_comparisons" to "genericComparisons",
        )
        private val ADOPTION_KNOWN_TABLES = setOf(
            "jobs", "source_scan_results", "source_scan_detector_counts", "source_scan_findings", "artifacts", "log_entries",
            "sandbox_owner", "sandbox_resources", "runner_identity", "storage_settings", "operations", "retention_holds",
            "storage_reservations", "resource_availability", "cleanup_runs", "cleanup_items", "toolchain_installations",
            "toolchain_installation_items", "toolchain_inventory", "toolchain_license_acceptances", "toolchain_removal_previews",
            "generic_build_requests", "generic_discovery_evidence", "generic_comparisons", "generic_resource_retries",
            "security_certificates", "principals", "pairing_invitations", "pairing_requests", "credentials", "revocation_events",
            "security_audit_events", "ownership_adoption_previews", "ownership_adoption_audit",
        )
        private val ADOPTION_SNAPSHOT_QUERIES = linkedMapOf(
            "jobs" to "SELECT * FROM jobs WHERE principal_id=? ORDER BY job_id",
            "operations" to "SELECT * FROM operations WHERE principal_id=? ORDER BY operation_id",
            "retentionHolds" to "SELECT * FROM retention_holds WHERE principal_id=? ORDER BY hold_id",
            "storageReservations" to "SELECT * FROM storage_reservations WHERE principal_id=? ORDER BY reservation_id",
            "cleanupRuns" to "SELECT * FROM cleanup_runs WHERE principal_id=? ORDER BY cleanup_run_id",
            "toolchainInstallations" to "SELECT * FROM toolchain_installations WHERE principal_id=? ORDER BY installation_id",
            "toolchainLicenseAcceptances" to "SELECT * FROM toolchain_license_acceptances WHERE principal_id=? ORDER BY runner_id,license_id,license_text_sha256",
            "toolchainRemovalPreviews" to "SELECT * FROM toolchain_removal_previews WHERE principal_id=? ORDER BY preview_id",
            "genericComparisons" to "SELECT * FROM generic_comparisons WHERE principal_id=? ORDER BY comparison_id",
            "cleanupItems" to "SELECT i.* FROM cleanup_items i JOIN cleanup_runs r ON r.cleanup_run_id=i.cleanup_run_id WHERE r.principal_id=? ORDER BY i.cleanup_run_id,i.item_id",
            "toolchainItems" to "SELECT i.* FROM toolchain_installation_items i JOIN toolchain_installations t ON t.installation_id=i.installation_id WHERE t.principal_id=? ORDER BY i.installation_id,i.ordinal",
            "genericRetries" to "SELECT r.* FROM generic_resource_retries r JOIN generic_comparisons c ON c.comparison_id=r.original_comparison_id WHERE c.principal_id=? ORDER BY r.retry_comparison_id",
            "availability" to "SELECT r.* FROM resource_availability r WHERE EXISTS (SELECT 1 FROM jobs j LEFT JOIN artifacts a ON a.job_id=j.job_id WHERE j.principal_id=? AND (r.resource_id=j.job_id OR r.resource_id=a.artifact_id)) ORDER BY r.resource_kind,r.resource_id",
        ) + listOf(
            "source_scan_results", "source_scan_detector_counts", "source_scan_findings", "artifacts", "log_entries",
            "sandbox_resources", "generic_build_requests", "generic_discovery_evidence",
        ).associateWith { table -> "SELECT t.* FROM $table t JOIN jobs j ON j.job_id=t.job_id WHERE j.principal_id=? ORDER BY t.rowid" }
        private val HttpStatusCodeTooManyRequests = io.ktor.http.HttpStatusCode.TooManyRequests
    }
}

@Serializable internal data class AdoptionPreview(
    val previewId: String,
    val sourcePrincipalId: String,
    val targetPrincipalId: String,
    val counts: Map<String, Int>,
    val expiresAt: String,
    val blockers: List<String> = emptyList(),
)

private class PairingRateLimiter(private val clock: Clock) {
    private data class Window(var minute: Long, var count: Int)
    private val sources = linkedMapOf<String, Window>()
    private var global = Window(-1, 0)
    @Synchronized fun allow(source: String): Boolean {
        val minute = Instant.now(clock).epochSecond / 60
        if (global.minute != minute) global = Window(minute, 0)
        val key = source.take(128)
        val local = sources.getOrPut(key) { Window(minute, 0) }
        if (local.minute != minute) { local.minute = minute; local.count = 0 }
        if (sources.size > 512) sources.entries.iterator().also { if (it.hasNext()) { it.next(); it.remove() } }
        if (global.count >= 30 || local.count >= 10) return false
        global.count++; local.count++; return true
    }
}

private fun randomBytes(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)
private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun ByteArray.hexSecurity(): String = joinToString("") { "%02x".format(it) }
internal fun hexSha256(value: String): String = sha256(value.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
private fun constantTime(left: String, right: String): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))
private fun isCanonical(value: String): Boolean = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
private fun isCanonicalSecret(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{43}")) && runCatching {
    val bytes = Base64.getUrlDecoder().decode(value)
    bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
}.getOrDefault(false)
private fun confirmationFingerprint(requestId: String, tokenHash: String, continuationHash: String): String {
    val bytes = sha256("$requestId:$tokenHash:$continuationHash".toByteArray())
    return bytes.take(6).joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
}

internal fun ApiException.Companion.unauthorized() = ApiException(io.ktor.http.HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Authentication is required.")
internal fun ApiException.Companion.unauthorizedPairing() = ApiException(io.ktor.http.HttpStatusCode.Unauthorized, "PAIRING_UNAVAILABLE", "Pairing is unavailable.")

/** Call inside the transaction that durably accepts a new client action, not ongoing Runner work. */
internal fun requireActivePrincipal(connection: Connection, principalId: String) {
    if (principalId == SQLiteJobStore.LOCAL_DEVELOPMENT_PRINCIPAL) return
    val active = connection.prepareStatement("SELECT 1 FROM principals WHERE principal_id=? AND kind='PAIRED' AND state='ACTIVE'").use {
        it.setString(1, principalId)
        it.executeQuery().use { rows -> rows.next() }
    }
    if (!active) throw ApiException.unauthorized()
}
