package com.sanka1610.reprodroid.runner

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path

data class RunnerConfig(
    val host: String,
    val port: Int,
    val stateDirectory: Path,
    val simulationStepDelayMillis: Long = 350,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RunnerConfig {
            val host = environment["REPRODROID_HOST"] ?: "127.0.0.1"
            require(host.isNotBlank()) { "REPRODROID_HOST must not be blank." }
            val nonLoopbackAllowed = environment["REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK"]
                ?.equals("true", ignoreCase = true) == true
            require(host in setOf("127.0.0.1", "::1") || nonLoopbackAllowed) {
                "Refusing an unauthenticated non-loopback bind. Set " +
                    "REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK=true to acknowledge the risk."
            }

            val configuredPort = environment["REPRODROID_PORT"]
            val port = if (configuredPort == null) {
                8080
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
            return RunnerConfig(host, port, stateDirectory)
        }
    }
}

fun main() {
    val config = RunnerConfig.fromEnvironment()
    if (config.host !in setOf("127.0.0.1", "::1")) {
        LoggerFactory.getLogger("ReproDroidRunner").warn(
            "Binding the unauthenticated Runner API to non-loopback address {}.",
            config.host,
        )
    }
    embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
        module = { runnerModule(config) },
    ).start(wait = true)
}

fun Application.module() {
    runnerModule(RunnerConfig.fromEnvironment())
}
