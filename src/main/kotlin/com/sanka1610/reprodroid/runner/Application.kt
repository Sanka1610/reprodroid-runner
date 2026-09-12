package com.sanka1610.reprodroid.runner

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.InetAddress
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.Path

data class RunnerConfig(
    val host: String,
    val port: Int,
    val stateDirectory: Path,
    val simulationStepDelayMillis: Long = 350,
    val realBuildEnabled: Boolean = false,
    val buildSandbox: BuildSandboxMode = BuildSandboxMode.HOST,
    val apiV2Enabled: Boolean = false,
    val transportMode: TransportMode = TransportMode.DEVELOPMENT_HTTP,
    val advertisedEndpoint: URI? = null,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RunnerConfig {
            val transportRaw = environment["REPRODROID_TRANSPORT_MODE"]
            require(transportRaw == null || transportRaw.isNotEmpty()) { "REPRODROID_TRANSPORT_MODE must not be empty." }
            val transportMode = transportRaw?.let { raw ->
                TransportMode.entries.singleOrNull { it.name == raw }
                    ?: throw IllegalArgumentException("REPRODROID_TRANSPORT_MODE must be DEVELOPMENT_HTTP or PAIRED_HTTPS.")
            } ?: TransportMode.DEVELOPMENT_HTTP
            val host = environment["REPRODROID_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "REPRODROID_HOST must not be blank." }
            require(environment["REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK"]?.equals("true", true) != true) {
                "REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK is no longer supported."
            }
            if (transportMode == TransportMode.DEVELOPMENT_HTTP) require(host in LOOPBACK_HOSTS) {
                "DEVELOPMENT_HTTP requires an exact loopback bind."
            } else require(!isAnyLocal(host)) { "PAIRED_HTTPS rejects wildcard bind addresses." }

            val configuredPort = environment["REPRODROID_PORT"]
            val port = if (configuredPort == null) {
                if (transportMode == TransportMode.PAIRED_HTTPS) 8443 else 8080
            } else {
                requireNotNull(configuredPort.toIntOrNull()) { "REPRODROID_PORT must be an integer." }
            }
            require(port in 1..65535) { "REPRODROID_PORT must be between 1 and 65535." }

            val defaultStateDirectory = Path(
                System.getProperty("user.home"),
                ".local",
                "state",
                "reprodroid-runner",
            )
            val configuredStateDirectory = environment["REPRODROID_STATE_DIR"]
            require(configuredStateDirectory == null || configuredStateDirectory.isNotBlank()) {
                "REPRODROID_STATE_DIR must not be blank."
            }
            val stateDirectory = configuredStateDirectory?.let(::Path) ?: defaultStateDirectory
            RunnerLogging.select(stateDirectory)
            val realBuildEnabled = environment["REPRODROID_ENABLE_REAL_BUILDS"]
                ?.equals("true", ignoreCase = true) == true
            val sandbox = environment["REPRODROID_BUILD_SANDBOX"]?.let { value ->
                BuildSandboxMode.entries.singleOrNull { it.name == value }
                    ?: throw IllegalArgumentException("SANDBOX_CONFIG_INVALID: REPRODROID_BUILD_SANDBOX must be HOST or DOCKER.")
            } ?: BuildSandboxMode.HOST
            val apiV2Enabled = transportMode == TransportMode.PAIRED_HTTPS ||
                environment["REPRODROID_ENABLE_API_V2"]?.equals("true", ignoreCase = true) == true
            require(transportMode == TransportMode.PAIRED_HTTPS || !apiV2Enabled || host in LOOPBACK_HOSTS) {
                "Development API v2 requires loopback bind."
            }
            val advertisedEndpoint = if (transportMode == TransportMode.PAIRED_HTTPS) {
                parsePairedEndpoint(environment["REPRODROID_ADVERTISED_ENDPOINT"], port)
            } else null
            return RunnerConfig(
                host,
                port,
                stateDirectory,
                realBuildEnabled = realBuildEnabled,
                buildSandbox = sandbox,
                apiV2Enabled = apiV2Enabled,
                transportMode = transportMode,
                advertisedEndpoint = advertisedEndpoint,
            )
        }

        private fun parsePairedEndpoint(value: String?, configuredPort: Int): URI {
            require(!value.isNullOrBlank()) { "PAIRED_HTTPS requires REPRODROID_ADVERTISED_ENDPOINT." }
            val uri = runCatching { URI(value) }.getOrNull()
                ?: throw IllegalArgumentException("REPRODROID_ADVERTISED_ENDPOINT is invalid.")
            require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && uri.port == configuredPort && !isAnyLocal(uri.host)
            ) { "REPRODROID_ADVERTISED_ENDPOINT must be an exact https origin using REPRODROID_PORT." }
            return URI("https", null, uri.host, uri.port, "", null, null)
        }

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1")
        private val WILDCARD_HOSTS = setOf("0.0.0.0", "::", "[::]")
        private fun isAnyLocal(host: String): Boolean = host in WILDCARD_HOSTS ||
            runCatching { InetAddress.getByName(host).isAnyLocalAddress }.getOrDefault(true)
    }
}

fun main(args: Array<String>) {
    val config = RunnerConfig.fromEnvironment()
    validateLocalCommand(args)
    // Initialize the database (including runnerId and SQLite12) before either CLI or server work.
    val store = SQLiteJobStore(config.stateDirectory)
    val security = RunnerSecurityStore(config.stateDirectory)
    security.runnerId()
    val ca = LocalCertificateAuthority(config.stateDirectory)
    if (args.isNotEmpty()) {
        runLocalCommand(args, config, security, ca, store)
        return
    }
    if (config.realBuildEnabled) {
        LoggerFactory.getLogger("ReproDroidRunner").warn(
            "REAL_TRUSTED execution is enabled (sandbox={}). Allowlisted Gradle builds can execute arbitrary code.",
            config.buildSandbox,
        )
    }
    if (config.transportMode == TransportMode.DEVELOPMENT_HTTP) {
        LoggerFactory.getLogger("ReproDroidRunner").warn("DEVELOPMENT_HTTP is unauthenticated and loopback-only.")
        embeddedServer(Netty, host = config.host, port = config.port, module = { runnerModule(config) }).start(wait = true)
    } else {
        val endpoint = requireNotNull(config.advertisedEndpoint)
        ca.acquireConnectorLease().use {
        val tls = ca.loadOrRenew(endpoint.host.removeSurrounding("[", "]"), { security.recordCertificates(it) }, security::verifyCertificates)
        security.verifyCertificates(tls)
        RenewingTlsContext(ca, security, endpoint.host.removeSurrounding("[", "]"), tls).use { renewing ->
        embeddedServer(
            factory = Netty,
            configure = {
                enableHttp2 = false
                enableH2c = false
                channelPipelineConfig = { renewing.configure(this) }
                sslConnector(tls.keyStore, "runner-leaf", { tls.password.copyOf() }, { tls.password.copyOf() }) {
                    host = config.host
                    port = config.port
                    enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
                }
            },
            module = { runnerModule(config) },
        ).start(wait = true)
        }
        }
    }
}

fun Application.module() {
    runnerModule(RunnerConfig.fromEnvironment())
}

private fun runLocalCommand(
    args: Array<String>,
    config: RunnerConfig,
    security: RunnerSecurityStore,
    ca: LocalCertificateAuthority,
    store: SQLiteJobStore,
) {
    val command = args.first()
    val endpoint = config.advertisedEndpoint
    when (command) {
        "security-init" -> {
            val host = (endpoint?.host ?: args.getOrNull(1) ?: "localhost").removeSurrounding("[", "]")
            security.requireUninitializedCertificates()
            val material = ca.initialize(security.runnerId(), host, beforeActivate = { security.recordCertificates(it) })
            security.verifyCertificates(material)
            println("runnerId=${security.runnerId()}")
            println("rootSpkiSha256=${material.rootPin}")
        }
        "security-rotate-root" -> {
            val host = (endpoint?.host ?: args.getOrNull(1) ?: error("endpoint host argument is required")).removeSurrounding("[", "]")
            val material = ca.initialize(security.runnerId(), host, replaceRoot = true) {
                security.recordCertificates(it, replaceRoot = true)
            }
            security.verifyCertificates(material)
            println("runnerId=${security.runnerId()}")
            println("rootSpkiSha256=${material.rootPin}")
        }
        "security-change-endpoint" -> {
            val host = requireNotNull(endpoint) { "PAIRED_HTTPS configuration is required." }.host.removeSurrounding("[", "]")
            val material = ca.changeEndpoint(host, { security.recordCertificates(it) }, security::verifyCertificates)
            security.verifyCertificates(material)
            println("runnerId=${security.runnerId()}")
            println("rootSpkiSha256=${material.rootPin}")
            println("endpoint=${endpoint.toASCIIString()}")
            println("Restart the paired Runner with this exact endpoint, then open a new manual pairing invitation.")
        }
        "pairing-open" -> {
            val origin = requireNotNull(endpoint) { "PAIRING_REQUIRES_PAIRED_CONFIG" }.toASCIIString()
            val material = ca.loadOrRenew(endpoint.host.removeSurrounding("[", "]"), validateExisting = security::verifyCertificates, renew = false)
            security.verifyCertificates(material)
            val payload = security.openInvitation(origin, material.rootPin)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(CLI_JSON.encodeToString(payload).toByteArray(Charsets.UTF_8))
            println(encoded)
            println("endpoint=${payload.endpoint}")
            println("runnerId=${payload.runnerId}")
            println("rootSpkiSha256=${payload.rootSpkiSha256}")
            println("invitationId=${payload.invitationId}")
            println("invitationSecret=${payload.invitationSecret}")
            println("expiresAt=${payload.expiresAt}")
        }
        "pairing-list" -> security.pendingCli().forEach { println(CLI_JSON.encodeToString(it)) }
        "pairing-approve" -> println(CLI_JSON.encodeToString(security.decide(requireArg(args, 1, "requestId"), true)))
        "pairing-reject" -> println(CLI_JSON.encodeToString(security.decide(requireArg(args, 1, "requestId"), false)))
        "principals-list" -> security.principals().forEach { println(CLI_JSON.encodeToString(it)) }
        "principal-revoke" -> println(CLI_JSON.encodeToString(security.revoke(requireArg(args, 1, "principalId"))))
        "adoption-preview" -> println(CLI_JSON.encodeToString(security.previewAdoption(requireArg(args, 1, "sourcePrincipalId"), requireArg(args, 2, "targetPrincipalId"))))
        "adoption-execute" -> println(CLI_JSON.encodeToString(security.executeAdoption(requireArg(args, 1, "previewId"))))
        "runner-log-export" -> printLogExportResult(
            RunnerLogExporter.exportRunnerLog(config.stateDirectory, Path(requireArg(args, 1, "output"))),
        )
        "job-log-export" -> printLogExportResult(
            RunnerLogExporter.exportJobLog(
                store,
                config.stateDirectory,
                requireArg(args, 1, "jobId"),
                Path(requireArg(args, 2, "output")),
            ),
        )
        else -> error("Unknown local command.")
    }
}

private fun requireArg(args: Array<String>, index: Int, label: String): String = args.getOrNull(index) ?: error("$label argument is required")
private fun validateLocalCommand(args: Array<String>) {
    if (args.isEmpty()) return
    val argumentCount = when (args[0]) {
        "security-init", "security-rotate-root" -> 1..2
        "pairing-open", "pairing-list", "principals-list", "security-change-endpoint" -> 1..1
        "pairing-approve", "pairing-reject", "principal-revoke", "adoption-execute" -> 2..2
        "adoption-preview" -> 3..3
        "runner-log-export" -> 2..2
        "job-log-export" -> 3..3
        else -> error("Unknown local command.")
    }
    require(args.size in argumentCount) { "Invalid local command arguments." }
}
private fun printLogExportResult(result: LogExportResult) {
    println("exportedBytes=${result.bytes}")
    println("range=${result.range}")
    println("missing=${result.missing}")
    println("truncated=${result.truncated}")
}
private val CLI_JSON = Json { explicitNulls = false; encodeDefaults = true }
