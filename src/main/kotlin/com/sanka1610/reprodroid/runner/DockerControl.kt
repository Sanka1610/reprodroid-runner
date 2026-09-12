package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal interface DockerControl {
    suspend fun command(
        arguments: List<String>,
        timeout: Duration = Duration.ofSeconds(15),
        maxOutputBytes: Int = 65_536,
    ): String
}

/** Separate from build logs: control output must never be silently truncated or parsed after CLI failure. */
internal class LocalDockerControl(private val workingDirectory: Path) : DockerControl {
    private val profile = DockerSandboxProfile()

    override suspend fun command(arguments: List<String>, timeout: Duration, maxOutputBytes: Int): String = runInterruptible {
        require(maxOutputBytes in 1..8 * 1024 * 1024)
        val executable = Path.of(profile.dockerExecutable).toRealPath()
        require(Files.isRegularFile(executable) && Files.isExecutable(executable))
        // Desktop WSL integration supplies a nobody-owned CLI on a read-only ISO9660 mount.
        // Native Linux CLI installations must instead be root-owned and not group/world-writable.
        require(Files.getFileStore(executable).isReadOnly ||
            (Files.getAttribute(executable, "unix:uid") == 0 &&
                (Files.getAttribute(executable, "unix:mode") as Int) and 0b010010 == 0))
        val process = ProcessBuilder(listOf(profile.dockerExecutable, "--host", profile.endpoint) + arguments)
            .directory(workingDirectory.toFile()).redirectErrorStream(true).apply {
                environment().clear()
                environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to workingDirectory.toString()))
            }.start()
        val output = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>()
        val reader = thread(name = "reprodroid-docker-control", isDaemon = true) {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxOutputBytes) error("Docker control output exceeded its limit.")
                        output.write(buffer, 0, count)
                    }
                }
            } catch (caught: Throwable) {
                failure.set(caught)
                process.destroyForcibly()
            }
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) error("Docker control timed out.")
            reader.join(1000)
            check(!reader.isAlive && failure.get() == null && process.exitValue() == 0) { "Docker control failed." }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString().trim()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            reader.interrupt()
        }
    }
}

internal class DockerRecoveryEngine(private val control: DockerControl) : SandboxRecoveryEngine {
    override suspend fun identity(endpoint: String): String {
        require(endpoint == DockerSandboxProfile().endpoint)
        val info = Json.parseToJsonElement(control.command(listOf("info", "--format", "{{json .}}"))).jsonObject
        return info.getValue("ID").jsonPrimitive.content.also {
            require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl))
        }
    }

    override suspend fun ownedContainers(endpoint: String, ownerId: String): List<SandboxContainerIdentity> {
        require(endpoint == DockerSandboxProfile().endpoint)
        val output = control.command(listOf("container", "ls", "--all", "--no-trunc", "--quiet", "--filter", "label=com.reprodroid.owner=$ownerId"))
        val identifiers = if (output.isBlank()) emptyList() else output.lines()
        require(identifiers.size <= 128 && identifiers.toSet().size == identifiers.size)
        return identifiers.map { id ->
            require(id.matches(Regex("[0-9a-f]{64}")))
            val inspected = inspect(id)
            SandboxContainerIdentity(
                inspected.getValue("Id").jsonPrimitive.content,
                inspected.getValue("Name").jsonPrimitive.content.removePrefix("/"),
                inspected.getValue("Config").jsonObject.getValue("Labels").jsonObject.mapValues { it.value.jsonPrimitive.content },
            ).also { require(it.id == id && it.labels["com.reprodroid.owner"] == ownerId) }
        }
    }

    override suspend fun remove(endpoint: String, containerId: String) {
        require(endpoint == DockerSandboxProfile().endpoint && containerId.matches(Regex("[0-9a-f]{64}")))
        // rm --force kills the container's cgroup and removes it; a separate list proves absence.
        control.command(listOf("container", "rm", "--force", containerId), Duration.ofSeconds(30))
    }

    suspend fun inspect(containerId: String): JsonObject = Json.parseToJsonElement(
        control.command(listOf("container", "inspect", containerId)),
    ).jsonArray.single().jsonObject
}
