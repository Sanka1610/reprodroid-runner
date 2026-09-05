package com.sanka1610.reprodroid.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

@Serializable
internal data class SandboxMount(val source: String, val destination: String, val readOnly: Boolean)

internal class DockerBuildSpec(
    val profile: DockerSandboxPolicy,
    val mounts: List<SandboxMount>,
    val environment: Map<String, String>,
    val workingDirectory: String = profile.source,
) {
    init {
        require(workingDirectory == profile.source || workingDirectory.startsWith("${profile.source}/"))
        require(".." !in workingDirectory.split('/'))
    }
    fun preflightMounts() {
        try {
            val started = System.nanoTime()
            fun deadline() { require(System.nanoTime() - started <= 60_000_000_000) }
            val readOnlyDestinations = setOfNotNull(profile.jdk, profile.sdk, profile.gradle)
            val destinations = setOf(profile.source, profile.home, profile.gradleHome, profile.jdk, profile.sdk) + listOfNotNull(profile.gradle)
            require(mounts.size == destinations.size && mounts.map { it.destination }.toSet() == destinations)
            for (mount in mounts) {
                deadline()
                val path = Path.of(mount.source)
                require(path.isAbsolute && path == path.normalize() && ',' !in mount.source && mount.source.none(Char::isISOControl))
                var ancestor = path.root
                for (component in path) {
                    ancestor = ancestor.resolve(component)
                    require(Files.isDirectory(ancestor, NOFOLLOW_LINKS) && !Files.isSymbolicLink(ancestor))
                }
                require(mount.readOnly == (mount.destination in readOnlyDestinations))
                require(Files.isReadable(path) && Files.isExecutable(path))
                if (!mount.readOnly) {
                    require(Files.getAttribute(path, "unix:uid") == profile.uid && Files.getAttribute(path, "unix:gid") == profile.gid)
                    require(Files.isWritable(path))
                    if (Files.getFileStore(path).usableSpace < profile.minimumFreeDiskBytes) {
                        throw TrustedBuildFailure("SANDBOX_RESOURCE_LIMIT_EXCEEDED", "At least 16 GiB of free Job disk space is required.")
                    }
                } else {
                    // Toolchain internal links may be used only inside this same read-only root.
                    Files.walk(path).use { entries ->
                        entries.forEach { entry ->
                            deadline()
                            if (Files.isSymbolicLink(entry)) require(entry.toRealPath().startsWith(path))
                        }
                    }
                }
            }
            val writable = mounts.filterNot { it.readOnly }.map { Path.of(it.source) }
            require(writable.all { candidate -> writable.none { it != candidate && candidate.startsWith(it) } })
            val workspace = writable.single { it.fileName.toString() == "source" }.parent
            require(writable.toSet() == setOf(workspace.resolve("source"), workspace.resolve("home"), workspace.resolve("gradle-user-home")))
            require(mounts.filter { it.readOnly }.none { mount ->
                val toolchain = Path.of(mount.source)
                workspace.startsWith(toolchain) || toolchain.startsWith(workspace)
            })
        } catch (failure: TrustedBuildFailure) { throw failure } catch (_: Exception) {
            throw TrustedBuildFailure("SANDBOX_MOUNT_INVALID", "A sandbox mount failed boundary or access verification.")
        }
    }

    fun createArguments(resource: SandboxResource, command: List<String>): List<String> = buildList {
        require(environment.keys == setOf("PATH", "JAVA_HOME", "HOME", "GRADLE_USER_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT"))
        addAll(listOf(
            "container", "create", "--pull=never", "--platform", profile.platform,
            "--name", resource.expectedName, "--user", "${profile.uid}:${profile.gid}",
            "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges:true",
            "--cpus", profile.cpuCount.toString(), "--cpuset-cpus", profile.cpuset,
            "--memory", profile.memoryBytes.toString(), "--memory-swap", profile.memorySwapBytes.toString(),
            "--pids-limit", profile.pids.toString(), "--network=bridge", "--restart=no",
            "--tmpfs", "/tmp:rw,nosuid,nodev,size=${profile.tmpfsBytes}",
            "--workdir", workingDirectory, "--entrypoint", command.first(),
        ))
        resource.labels.forEach { (key, value) -> addAll(listOf("--label", "$key=$value")) }
        mounts.forEach { mount ->
            addAll(listOf("--mount", "type=bind,source=${mount.source},target=${mount.destination},bind-propagation=rprivate" + if (mount.readOnly) ",readonly" else ""))
        }
        environment.forEach { (key, value) -> addAll(listOf("--env", "$key=$value")) }
        add(profile.image)
        addAll(command.drop(1))
    }

    fun validateInspection(inspected: JsonObject, resource: SandboxResource, imageId: String, command: List<String>) {
        require(inspected.string("Id") == resource.containerId && inspected.string("Name") == "/${resource.expectedName}")
        require(inspected.string("Image") == imageId && inspected.string("Platform") == "linux")
        val config = inspected.getValue("Config").jsonObject
        require(config.string("Image") == profile.image && config.string("User") == "${profile.uid}:${profile.gid}")
        require(config.string("WorkingDir") == workingDirectory && config.getValue("Entrypoint").jsonArray.map { it.jsonPrimitive.content } == listOf(command.first()))
        require(config.getValue("Cmd").jsonArray.map { it.jsonPrimitive.content } == command.drop(1))
        require(config.getValue("Env").jsonArray.map { it.jsonPrimitive.content }.toSet() == environment.map { (key, value) -> "$key=$value" }.toSet())
        val labels = config.getValue("Labels").jsonObject
        require(resource.labels.all { (key, value) -> labels[key]?.jsonPrimitive?.content == value })
        val host = inspected.getValue("HostConfig").jsonObject
        require(host.boolean("ReadonlyRootfs") && !host.boolean("Privileged") && !host.boolean("PublishAllPorts"))
        require(host.string("NetworkMode") == "bridge" && host.string("PidMode") == "" && host.string("IpcMode") == "private")
        require(host.getValue("RestartPolicy").jsonObject.string("Name") == "no")
        require(host.number("NanoCpus") == profile.cpuCount * 1_000_000_000L && host.string("CpusetCpus") == profile.cpuset)
        require(host.number("Memory") == profile.memoryBytes && host.number("MemorySwap") == profile.memorySwapBytes && host.number("PidsLimit") == profile.pids.toLong())
        require(host.getValue("CapDrop").jsonArray.map { it.jsonPrimitive.content }.toSet() == setOf("ALL"))
        require(host.getValue("SecurityOpt").jsonArray.map { it.jsonPrimitive.content }.toSet() == setOf("no-new-privileges:true"))
        for (field in listOf("CapAdd", "Devices", "DeviceRequests", "GroupAdd", "Binds", "Links", "VolumesFrom", "PortBindings")) {
            require(host[field].let { it == null || it == JsonNull || it == JsonArray(emptyList()) || it == JsonObject(emptyMap()) })
        }
        val tmpfs = host.getValue("Tmpfs").jsonObject
        require(tmpfs.keys == setOf("/tmp"))
        val tmpfsOptions = tmpfs.string("/tmp").split(',').toSet()
        require(tmpfsOptions == setOf("rw", "nosuid", "nodev", "size=${profile.tmpfsBytes}"))
        val actualMounts = inspected.getValue("Mounts").jsonArray
        require(actualMounts.size == mounts.size)
        require(actualMounts.map { it.jsonObject.string("Destination") }.toSet() == mounts.map { it.destination }.toSet())
        for (actual in actualMounts.map { it.jsonObject }) {
            val expected = mounts.single { it.destination == actual.string("Destination") }
            require(actual.string("Type") == "bind" && actual.string("Source") == expected.source)
            require(actual.boolean("RW") != expected.readOnly && actual.string("Propagation") == "rprivate")
        }
    }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
internal fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
internal fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.let { require(!it.isString); it.boolean }
