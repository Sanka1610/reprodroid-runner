package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Duration

@Serializable
internal data class DockerExecutionAudit(
    val ownerId: String,
    val engineId: String,
    val snapshotSha256: String,
    val imageId: String,
    val preflightAttemptId: String,
    val buildAttemptId: String,
    val preflightContainerId: String,
    val buildContainerId: String,
    val mounts: List<SandboxMount>,
    val sandbox: SandboxEvidence,
    val java: PublicJavaRuntime,
    val operatingSystem: String,
    val exitCode: Int,
    val oomKilled: Boolean,
    val buildInspection: String,
    val preflightInspection: String,
    val preflightOutput: String,
    val engineInfo: String,
    val imageInspection: String,
)

internal data class DockerBuildResult(val audit: DockerExecutionAudit, val failure: TrustedBuildFailure?)

internal class DockerBuildExecutor(
    private val store: SQLiteJobStore,
    private val lifecycle: SandboxLifecycle,
    private val control: DockerControl,
    private val processExecutor: ProcessExecutor,
    private val stateDirectory: Path,
) {
    private val inspection = DockerRecoveryEngine(control)

    suspend fun execute(
        job: StoredJob, recipe: BuildRecipe, source: Path, home: Path, gradleHome: Path,
        java: BuildJavaRuntime, sdk: Path,
    ): DockerBuildResult {
        val snapshot = job.sandbox ?: throw invalidSandboxSnapshot()
        val profile = snapshot.validatedProfile() ?: throw invalidSandboxSnapshot()
        profile.requireSupported(recipe)
        if (lifecycle.cleanupPending) throw TrustedBuildFailure("SANDBOX_CLEANUP_PENDING", "Sandbox recovery must complete before execution.")
        val environment = mapOf(
            "PATH" to "${profile.jdk}/bin:/usr/bin:/bin", "JAVA_HOME" to profile.jdk, "HOME" to profile.home,
            "GRADLE_USER_HOME" to profile.gradleHome, "ANDROID_HOME" to profile.sdk, "ANDROID_SDK_ROOT" to profile.sdk,
        )
        val spec = DockerBuildSpec(profile, listOf(
            SandboxMount(source.toString(), profile.source, false), SandboxMount(home.toString(), profile.home, false),
            SandboxMount(gradleHome.toString(), profile.gradleHome, false), SandboxMount(java.home.toString(), profile.jdk, true),
            SandboxMount(sdk.toString(), profile.sdk, true),
        ), environment)
        spec.preflightMounts()
        val resources = mutableListOf<SandboxResource>()
        var failureCode = "SANDBOX_ENGINE_UNAVAILABLE"
        var originalFailure: Throwable? = null
        try {
            val info = Json.parseToJsonElement(control.command(listOf("info", "--format", "{{json .}}"))).jsonObject
            val engineId = info.string("ID")
            val engineVersion = info.string("ServerVersion")
            require(info.string("OSType") == "linux" && info.string("Architecture") in setOf("x86_64", "amd64"))
            require(info.getValue("SecurityOptions").jsonArray.any { it.jsonPrimitive.content == "name=seccomp,profile=builtin" })
            failureCode = "SANDBOX_RESOURCE_LIMIT_EXCEEDED"
            require(info.number("NCPU") >= profile.cpuCount && info.number("MemTotal") >= profile.memoryBytes)
            failureCode = "SANDBOX_IMAGE_INVALID"
            val image = Json.parseToJsonElement(control.command(listOf("image", "inspect", profile.image))).jsonArray.single().jsonObject
            val imageId = image.string("Id")
            require(imageId.matches(Regex("sha256:[0-9a-f]{64}")))
            require(image.string("Os") == "linux" && image.string("Architecture") == "amd64")
            require(image.getValue("RepoDigests").jsonArray.any { it.jsonPrimitive.content == profile.image })
            val sandbox = SandboxEvidence(
                BuildSandboxMode.DOCKER, profile.profileId, DockerSandboxProfile.IMAGE_DIGEST, profile.platform,
                engineVersion, profile.networkMode,
                SandboxLimits(profile.cpuCount, profile.cpuset, profile.memoryBytes, profile.memorySwapBytes, profile.pids, profile.tmpfsBytes),
                SandboxIsolation(profile.uid, profile.gid, true, true, true, "DEFAULT", true, true, false, false),
            ).also { it.validate() }
            failureCode = "SANDBOX_TOOLCHAIN_MISMATCH"
            val probeCommand = listOf("/bin/sh", "-ec", TOOLCHAIN_PROBE)
            val preflight = create(job.jobId, SandboxResourceRole.PREFLIGHT, engineId, imageId, spec, probeCommand, resources)
            val preflightInspection = requireNotNull(store.sandboxResources().single { it.attemptId == preflight.attemptId }.observationJson)
            val probe = control.command(listOf("container", "start", "--attach", requireNotNull(preflight.containerId)), Duration.ofSeconds(60))
            val probeState = inspection.inspect(preflight.containerId).getValue("State").jsonObject
            require(!probeState.boolean("Running") && probeState.number("ExitCode") == 0L && !probeState.boolean("OOMKilled"))
            val measuredJava = PublicJavaRuntime(property(probe, "java.version"), property(probe, "java.vendor"))
            val measuredOs = listOf("os.name", "os.version", "os.arch").joinToString(" ") { property(probe, it) }
            require(measuredJava == PublicJavaRuntime(java.version, java.vendor))
            require(property(probe, "user.home") == profile.home && property(probe, "java.home") == profile.jdk)
            require(Regex("(?m)^CapEff:\\s+0+$").containsMatchIn(probe) && Regex("(?m)^NoNewPrivs:\\s+1$").containsMatchIn(probe))
            require(Regex("(?m)^Seccomp:\\s+2$").containsMatchIn(probe))
            // Remove the probe before the build; the following intent returns COMPLETE to PENDING.
            if (!lifecycle.recover()) throw TrustedBuildFailure("SANDBOX_CLEANUP_FAILED", "Sandbox preflight cleanup is pending.")
            failureCode = "SANDBOX_START_FAILED"
            val buildCommand = listOf(
                "${profile.jdk}/bin/java", "-Duser.home=${profile.home}", "-Dgradle.user.home=${profile.gradleHome}",
                "-classpath", "${profile.source}/gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain",
            ) + gradleOptions(recipe) + recipe.tasks
            val build = create(job.jobId, SandboxResourceRole.BUILD, engineId, imageId, spec, buildCommand, resources)
            val buildInspection = requireNotNull(store.sandboxResources().single { it.attemptId == build.attemptId }.observationJson)
            if (!store.transitionIfActive(job.jobId, JobState.BUILDING, 55, "Starting confirmed Gradle tasks inside docker-microg-v1.")) {
                throw CancellationException("Job is no longer active.")
            }
            var cliExitCode: Int? = null
            var timedOut = false
            try {
                val result = processExecutor.execute(
                    listOf(profile.dockerExecutable, "--host", profile.endpoint, "container", "start", "--attach", requireNotNull(build.containerId)),
                    stateDirectory, mapOf("PATH" to "/usr/bin:/bin", "HOME" to stateDirectory.toString()), recipe.timeout,
                ) { line -> store.appendLog(job.jobId, LogLevel.INFO, "[gradle] $line") }
                cliExitCode = result.exitCode
            } catch (_: ProcessTimeoutException) {
                timedOut = true
            }
            val finishedInspection = inspection.inspect(requireNotNull(build.containerId))
            val buildState = finishedInspection.getValue("State").jsonObject
            val oomKilled = buildState.boolean("OOMKilled")
            val exitCode = buildState.number("ExitCode").toInt()
            val buildFailure = classifyDockerBuildCompletion(buildState, cliExitCode, timedOut)
            val audit = DockerExecutionAudit(
                lifecycle.ownerId, engineId, requireNotNull(snapshot.profileSha256), imageId, preflight.attemptId, build.attemptId,
                requireNotNull(preflight.containerId), requireNotNull(build.containerId), spec.mounts, sandbox, measuredJava, measuredOs, exitCode, oomKilled,
                buildInspection, preflightInspection, probe, info.toString(), image.toString(),
            )
            audit { store.recordSandboxObservation(build.attemptId, Json.encodeToString(audit)) }
            buildFailure?.let { failure -> audit { store.recordSandboxFailure(build.attemptId, failure.code, null) } }
            return DockerBuildResult(audit, buildFailure)
        } catch (failure: Throwable) {
            originalFailure = failure
            resources.lastOrNull()?.let { resource ->
                runCatching {
                    store.recordSandboxFailure(resource.attemptId,
                        (failure as? TrustedBuildFailure)?.code ?: if (failure is CancellationException) "CANCELLED" else failureCode, null)
                }.onFailure { lifecycle.auditFailure() }
            }
            if (failure is CancellationException || failure is TrustedBuildFailure) throw failure
            throw TrustedBuildFailure(failureCode, "Sandbox verification or execution failed; the build was not accepted.").also { it.initCause(failure) }
        } finally {
            if (resources.isNotEmpty()) {
                val removed = lifecycle.recover()
                if (!removed) {
                    runCatching { store.appendLog(job.jobId, LogLevel.WARN, "Sandbox cleanup remains PENDING; cancellation does not prove process termination.") }
                    resources.forEach { resource ->
                        runCatching {
                            store.recordSandboxFailure(resource.attemptId,
                                (originalFailure as? TrustedBuildFailure)?.code ?: if (originalFailure is CancellationException) "CANCELLED" else null,
                                "SANDBOX_CLEANUP_FAILED")
                        }
                    }
                    if (originalFailure !is CancellationException) {
                        throw TrustedBuildFailure("SANDBOX_CLEANUP_FAILED", "Owned sandbox resources could not be confirmed removed.")
                    }
                }
            }
        }
    }

    private suspend fun create(
        jobId: String, role: SandboxResourceRole, engineId: String, imageId: String,
        spec: DockerBuildSpec, command: List<String>, resources: MutableList<SandboxResource>,
    ): SandboxResource {
        val resource = lifecycle.intent(jobId, role, engineId)
        resources += resource // Include the create-before-ID failure window in unconditional cleanup.
        try {
            val containerId = control.command(spec.createArguments(resource, command), Duration.ofSeconds(30))
            require(containerId.matches(Regex("[0-9a-f]{64}")))
            audit { store.recordSandboxContainerId(resource.attemptId, containerId) }
            val created = resource.copy(containerId = containerId)
            val inspected = inspection.inspect(containerId)
            spec.validateInspection(inspected, created, imageId, command)
            require(inspection.identity(resource.endpoint) == engineId)
            audit { store.recordSandboxObservation(resource.attemptId, inspected.toString()) }
            return created
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is TrustedBuildFailure) throw failure
            throw TrustedBuildFailure("SANDBOX_START_FAILED", "Container creation or pre-start inspection failed.")
        }
    }

    private fun audit(block: () -> Unit) {
        try { block() } catch (_: Exception) {
            lifecycle.auditFailure()
            throw TrustedBuildFailure("SANDBOX_AUDIT_PERSISTENCE_FAILED", "Sandbox audit persistence failed; execution is blocked.")
        }
    }

    private fun property(output: String, key: String): String =
        Regex("(?m)^\\s*${Regex.escape(key)} = (.+)$").findAll(output).single().groupValues[1].trim()

    private companion object {
        val TOOLCHAIN_PROBE = """
            test "$(id -u)" = 1000
            test "$(id -g)" = 1000
            cat /proc/self/status
            /opt/jdk/bin/java -Duser.home=/home/ubuntu -XshowSettings:properties -version
            test -r /opt/android-sdk/platforms/android-36/android.jar
            /opt/android-sdk/build-tools/36.0.0/aapt2 version
        """.trimIndent()
    }
}

/** CLI return is not a substitute for the engine's observation of a started, exited process. */
internal fun classifyDockerBuildCompletion(state: JsonObject, cliExitCode: Int?, timedOut: Boolean): TrustedBuildFailure? {
    if (state.boolean("OOMKilled")) return TrustedBuildFailure("SANDBOX_RESOURCE_LIMIT_EXCEEDED", "The engine reported that the build was OOM-killed.")
    if (timedOut) return TrustedBuildFailure("PROCESS_TIMEOUT", "The Gradle build exceeded the recipe timeout.")
    if (state.string("Status") == "created" || state.string("StartedAt").startsWith("0001-")) {
        return TrustedBuildFailure("SANDBOX_START_FAILED", "The engine did not report that the build process started.")
    }
    if (cliExitCode != 0 || state.boolean("Running") || state.string("Status") != "exited" || state.number("ExitCode") != 0L) {
        return TrustedBuildFailure("GRADLE_BUILD_FAILED", "The engine did not report a successful completed Gradle process.")
    }
    return null
}
