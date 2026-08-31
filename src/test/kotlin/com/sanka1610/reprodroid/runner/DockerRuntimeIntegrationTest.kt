package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.time.Duration
import java.sql.DriverManager
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Operator opt-in: uses the fixed preloaded image and real Docker, but no repository/network/build-script code. */
@EnabledIfEnvironmentVariable(named = "REPRODROID_TEST_DOCKER", matches = "true")
class DockerRuntimeIntegrationTest {
    // /tmp may be a small tmpfs. Keep the evidence on the same disk as the production workspace.
    private val state: Path = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "docker-runtime-")

    @Test fun `real fixed profile measures toolchain runs fixture and removes both containers`() = runBlocking {
        val result = requireNotNull(fixture())
        assertNull(result.failure)
        assertFalse(result.audit.oomKilled)
        assertEquals(0, result.audit.exitCode)
    }

    @Test fun `real cancellation removes running container without changing CANCELLED`() = runBlocking {
        assertNull(fixture(cancel = true))
    }

    @Test fun `real timeout removes running container and reports timeout`() = runBlocking {
        assertEquals("PROCESS_TIMEOUT", fixture(timeout = true)?.failure?.code)
    }

    @Test fun `exit 137 without observed OOM remains ordinary build failure`() = runBlocking {
        val result = requireNotNull(fixture(exit137 = true))
        assertEquals("GRADLE_BUILD_FAILED", result.failure?.code)
        assertFalse(result.audit.oomKilled)
    }

    @Test fun `missing engine fails before container creation without fallback`() = runBlocking { fixture(fault = Fault.ENGINE_UNAVAILABLE); Unit }
    @Test fun `missing pinned image fails before container creation without pull`() = runBlocking { fixture(fault = Fault.IMAGE_MISSING); Unit }
    @Test fun `wrong image digest fails before container creation`() = runBlocking { fixture(fault = Fault.IMAGE_DIGEST); Unit }
    @Test fun `insufficient observed CPU capacity fails before container creation`() = runBlocking { fixture(fault = Fault.CPU_CAPACITY); Unit }
    @Test fun `failure before create reconciles durable intent with absence`() = runBlocking { fixture(fault = Fault.BEFORE_CREATE); Unit }
    @Test fun `lost create reply recovers full ID from actual labels before removal`() = runBlocking { fixture(fault = Fault.CREATE_REPLY_LOST); Unit }
    @Test fun `ID persistence failure never starts source and blocks until DB recovery`() = runBlocking { fixture(fault = Fault.ID_PERSISTENCE); Unit }
    @Test fun `initial audit persistence failure never starts source`() = runBlocking { fixture(fault = Fault.INITIAL_AUDIT); Unit }
    @Test fun `final audit persistence failure removes source container without publishing success`() = runBlocking { fixture(fault = Fault.FINAL_AUDIT); Unit }
    @Test fun `cleanup write failure keeps pending despite actual removal until repeated recovery`() = runBlocking { fixture(fault = Fault.CLEANUP_PERSISTENCE); Unit }
    @Test fun `invalid actual inspection prevents starting source`() = runBlocking { fixture(fault = Fault.BUILD_INSPECTION); Unit }

    private enum class Fault(val expectedCode: String, val startsBuild: Boolean = false, val noResources: Boolean = false) {
        NONE(""),
        ENGINE_UNAVAILABLE("SANDBOX_ENGINE_UNAVAILABLE", noResources = true),
        IMAGE_MISSING("SANDBOX_IMAGE_INVALID", noResources = true),
        IMAGE_DIGEST("SANDBOX_IMAGE_INVALID", noResources = true),
        CPU_CAPACITY("SANDBOX_RESOURCE_LIMIT_EXCEEDED", noResources = true),
        BEFORE_CREATE("SANDBOX_START_FAILED"), CREATE_REPLY_LOST("SANDBOX_START_FAILED"),
        ID_PERSISTENCE("SANDBOX_CLEANUP_FAILED"), INITIAL_AUDIT("SANDBOX_AUDIT_PERSISTENCE_FAILED"),
        FINAL_AUDIT("SANDBOX_AUDIT_PERSISTENCE_FAILED", startsBuild = true),
        CLEANUP_PERSISTENCE("SANDBOX_CLEANUP_FAILED", startsBuild = true), BUILD_INSPECTION("SANDBOX_START_FAILED"),
    }

    private suspend fun fixture(cancel: Boolean = false, timeout: Boolean = false, exit137: Boolean = false, fault: Fault = Fault.NONE): DockerBuildResult? = coroutineScope {
        val store = SQLiteJobStore(state)
        val recipe = BuildRecipeRegistry.defaultRecipes.single { it.id == DockerSandboxProfile().recipeId }
        val id = store.createJob(CreateJobRequest(ExecutionMode.REAL_TRUSTED, recipe.repositoryUrl, recipe.revision), BuildSandboxMode.DOCKER).jobId
        store.beginResolvingRealJob(id, recipe)
        store.awaitRealConfirmation(id, "a".repeat(40))
        store.confirmRealJob(id, "a".repeat(40))
        store.updateState(id, JobState.VERIFYING_WRAPPER, 40)
        val workspace = Files.createDirectories(state.resolve("workspaces/$id"))
        val source = Files.createDirectories(workspace.resolve("source"))
        val home = Files.createDirectories(workspace.resolve("home"))
        val gradleHome = Files.createDirectories(workspace.resolve("gradle-user-home"))
        val javaSource = Files.createDirectories(state.resolve("fixture-source/org/gradle/wrapper")).resolve("GradleWrapperMain.java")
        Files.writeString(javaSource, """
            package org.gradle.wrapper;
            public class GradleWrapperMain {
                public static void main(String[] arguments) throws Exception {
                    if (!System.getProperty("user.home").equals("/home/ubuntu")) throw new AssertionError("user.home");
                    if (System.getenv("DOCKER_HOST") != null || System.getenv("JAVA_TOOL_OPTIONS") != null) throw new AssertionError("environment");
                    for (String path : new String[] {"/", "/opt/jdk", "/opt/android-sdk"}) {
                        if (!java.nio.file.Files.getFileStore(java.nio.file.Path.of(path)).isReadOnly()) throw new AssertionError("readonly mount");
                    }
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("/home/ubuntu/.android"));
                    if (java.nio.file.Files.exists(java.nio.file.Path.of("/var/run/docker.sock"))) throw new AssertionError("socket");
                    // Read the actual cgroup-v2 controls; this does not induce host memory/PID exhaustion.
                    checkControl("memory.max", "8589934592");
                    checkControl("memory.swap.max", "0");
                    checkControl("pids.max", "1024");
                    checkControl("cpuset.cpus.effective", "0-7");
                    String[] cpu = java.nio.file.Files.readString(java.nio.file.Path.of("/sys/fs/cgroup/cpu.max")).trim().split(" +");
                    if (Long.parseLong(cpu[0]) != 8 * Long.parseLong(cpu[1])) throw new AssertionError("cpu quota");
                    if (java.nio.file.Files.getFileStore(java.nio.file.Path.of("/tmp")).getTotalSpace() != 1073741824L) throw new AssertionError("tmpfs capacity");
                    System.out.println("REPRODROID_DOCKER_FIXTURE_COMPLETE");
                    if (java.nio.file.Files.exists(java.nio.file.Path.of("/work/source/exit137"))) System.exit(137);
                    while (java.nio.file.Files.exists(java.nio.file.Path.of("/work/source/hold"))) Thread.sleep(1000);
                }
                private static void checkControl(String name, String expected) throws Exception {
                    String actual = java.nio.file.Files.readString(java.nio.file.Path.of("/sys/fs/cgroup/" + name)).trim();
                    if (!actual.equals(expected)) throw new AssertionError("cgroup " + name);
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "18", javaSource.toString()))
        val wrapper = Files.createDirectories(source.resolve("gradle/wrapper")).resolve("gradle-wrapper.jar")
        JarOutputStream(Files.newOutputStream(wrapper)).use { jar ->
            jar.putNextEntry(JarEntry("org/gradle/wrapper/GradleWrapperMain.class"))
            Files.copy(javaSource.resolveSibling("GradleWrapperMain.class"), jar)
            jar.closeEntry()
        }
        val javaHome = Path.of(requireNotNull(System.getenv("REPRODROID_JDK_18_HOME")))
        val release = Properties().apply { Files.newBufferedReader(javaHome.resolve("release")).use(::load) }
        val java = BuildJavaRuntime(javaHome, javaHome.resolve("bin/java"), release.getProperty("JAVA_VERSION").trim('"'), release.getProperty("IMPLEMENTOR").trim('"'))
        if (cancel || timeout) Files.createFile(source.resolve("hold"))
        if (exit137) Files.createFile(source.resolve("exit137"))
        val realControl = LocalDockerControl(state)
        var buildContainerId: String? = null
        var buildStarts = 0
        val control = object : DockerControl {
            override suspend fun command(arguments: List<String>, timeout: Duration): String {
                if (arguments.first() == "info" && fault == Fault.ENGINE_UNAVAILABLE) error("fixture unavailable engine")
                if (arguments.take(2) == listOf("image", "inspect") && fault == Fault.IMAGE_MISSING) error("fixture missing image")
                val creatingBuild = arguments.take(2) == listOf("container", "create") && arguments.contains("com.reprodroid.role=BUILD")
                if (creatingBuild && fault == Fault.BEFORE_CREATE) error("fixture failure before Docker create")
                val output = realControl.command(arguments, timeout)
                if (arguments.first() == "info" && fault == Fault.CPU_CAPACITY) {
                    return JsonObject(Json.parseToJsonElement(output).jsonObject + ("NCPU" to JsonPrimitive(4))).toString()
                }
                if (arguments.take(2) == listOf("image", "inspect") && fault == Fault.IMAGE_DIGEST) {
                    val image = Json.parseToJsonElement(output).jsonArray.single().jsonObject
                    return JsonArray(listOf(JsonObject(image + ("RepoDigests" to JsonArray(emptyList()))))).toString()
                }
                if (creatingBuild) {
                    buildContainerId = output
                    val trigger = when (fault) {
                        Fault.ID_PERSISTENCE -> "BEFORE UPDATE OF container_id ON sandbox_resources WHEN NEW.role='BUILD'"
                        Fault.INITIAL_AUDIT -> "BEFORE UPDATE OF observation_json ON sandbox_resources WHEN NEW.role='BUILD'"
                        Fault.FINAL_AUDIT -> "BEFORE UPDATE OF observation_json ON sandbox_resources WHEN NEW.role='BUILD' AND OLD.observation_json IS NOT NULL"
                        Fault.CLEANUP_PERSISTENCE -> "BEFORE UPDATE OF removed ON sandbox_resources WHEN NEW.role='BUILD' AND NEW.removed=1"
                        else -> null
                    }
                    if (trigger != null) sql("CREATE TRIGGER fixture_fault $trigger BEGIN SELECT RAISE(ABORT, 'fixture'); END")
                    if (fault == Fault.CREATE_REPLY_LOST) error("fixture lost reply after actual Docker create")
                }
                if (fault == Fault.BUILD_INSPECTION && arguments.take(2) == listOf("container", "inspect") && arguments.last() == buildContainerId) {
                    val observed = Json.parseToJsonElement(output).jsonArray.single().jsonObject
                    val host = JsonObject(observed.getValue("HostConfig").jsonObject + ("Privileged" to JsonPrimitive(true)))
                    return JsonArray(listOf(JsonObject(observed + ("HostConfig" to host)))).toString()
                }
                return output
            }
        }
        val lifecycle = SandboxLifecycle(store, DockerRecoveryEngine(control), store.sandboxOwnerId())
        assertTrue(lifecycle.recover())
        val process = object : ProcessExecutor {
            override suspend fun execute(command: List<String>, workingDirectory: Path, environment: Map<String, String>,
                timeout: Duration, captureOutput: Boolean, onOutput: (String) -> Unit): ProcessResult {
                buildStarts++
                return SystemProcessExecutor().execute(command, workingDirectory, environment, if (this@DockerRuntimeIntegrationTest.forceTimeout) Duration.ofSeconds(1) else timeout, captureOutput, onOutput)
            }
        }
        forceTimeout = timeout
        val executor = DockerBuildExecutor(store, lifecycle, control, process, state)
        val work = async(Dispatchers.IO) {
            runCatching {
                executor.execute(requireNotNull(store.getStoredJob(id)), recipe, source, home, gradleHome, java,
                    Path.of(requireNotNull(System.getenv("ANDROID_SDK_ROOT"))))
            }
        }
        val result = if (cancel) {
            withTimeout(30_000) {
                while (store.getLogs(id, 0, 100)?.entries?.none { "REPRODROID_DOCKER_FIXTURE_COMPLETE" in it.message } != false) delay(50)
            }
            store.cancelIfActive(id)
            work.cancelAndJoin()
            assertEquals(JobState.CANCELLED, store.getJob(id)?.state)
            null
        } else {
            val completed = work.await()
            if (fault == Fault.NONE) completed.getOrThrow() else {
                val failure = completed.exceptionOrNull()
                assertTrue(failure is TrustedBuildFailure, "Expected a typed sandbox fault, got $failure")
                assertEquals(fault.expectedCode, (failure as TrustedBuildFailure).code)
                assertEquals(if (fault.startsBuild) 1 else 0, buildStarts)
                assertTrue(requireNotNull(store.getJob(id)).artifacts.isEmpty())
                assertNotEquals(JobState.SUCCEEDED, store.getJob(id)?.state)
                if (fault in setOf(Fault.ID_PERSISTENCE, Fault.CLEANUP_PERSISTENCE)) {
                    assertTrue(lifecycle.cleanupPending)
                    assertEquals(SandboxCleanupStatus.PENDING, store.getJob(id)?.sandbox?.cleanupStatus)
                    val existing = DockerRecoveryEngine(realControl).ownedContainers(DockerSandboxProfile().endpoint, lifecycle.ownerId)
                    assertEquals(if (fault == Fault.ID_PERSISTENCE) 1 else 0, existing.size)
                    if (fault == Fault.ID_PERSISTENCE) {
                        assertEquals("created", DockerRecoveryEngine(realControl).inspect(requireNotNull(buildContainerId)).getValue("State").jsonObject.string("Status"))
                        assertEquals("SANDBOX_AUDIT_PERSISTENCE_FAILED", store.sandboxResources().last().buildFailureCode)
                    }
                    sql("DROP TRIGGER fixture_fault")
                    assertTrue(lifecycle.recover())
                }
                null
            }
        }
        if (fault.noResources) {
            assertTrue(store.sandboxResources().isEmpty())
            assertEquals(SandboxCleanupStatus.NOT_CREATED, store.getJob(id)?.sandbox?.cleanupStatus)
            return@coroutineScope null
        }
        assertEquals(SandboxCleanupStatus.COMPLETE, store.getJob(id)?.sandbox?.cleanupStatus)
        assertEquals(2, store.sandboxResources().size)
        assertTrue(store.sandboxResources().all { it.removed })
        assertTrue(DockerRecoveryEngine(control).ownedContainers(DockerSandboxProfile().endpoint, lifecycle.ownerId).isEmpty())
        if (result != null && result.failure == null) validatePublication(store, id, recipe, result.audit)
        result
    }

    private fun validatePublication(store: SQLiteJobStore, jobId: String, recipe: BuildRecipe, audit: DockerExecutionAudit) {
        // Mutate real inspect observations locally; never launch weakened containers as a negative test.
        val profile = DockerSandboxProfile()
        val environment = mapOf("PATH" to "${profile.jdk}/bin:/usr/bin:/bin", "JAVA_HOME" to profile.jdk,
            "HOME" to profile.home, "GRADLE_USER_HOME" to profile.gradleHome, "ANDROID_HOME" to profile.sdk, "ANDROID_SDK_ROOT" to profile.sdk)
        val spec = DockerBuildSpec(profile, audit.mounts, environment)
        val observed = Json.parseToJsonElement(audit.buildInspection).jsonObject
        val resource = store.sandboxResources().single { it.role == SandboxResourceRole.BUILD }
        val config = observed.getValue("Config").jsonObject
        val command = (config.getValue("Entrypoint").jsonArray + config.getValue("Cmd").jsonArray).map { it.jsonPrimitive.content }
        for ((key, value) in mapOf<String, JsonElement>(
            "ReadonlyRootfs" to JsonPrimitive(false), "Privileged" to JsonPrimitive(true), "PublishAllPorts" to JsonPrimitive(true),
            "NetworkMode" to JsonPrimitive("host"), "PidMode" to JsonPrimitive("host"), "IpcMode" to JsonPrimitive("host"),
            "NanoCpus" to JsonPrimitive(1), "CpusetCpus" to JsonPrimitive("0"), "Memory" to JsonPrimitive(0),
            "MemorySwap" to JsonPrimitive(-1), "PidsLimit" to JsonPrimitive(0),
            "CapDrop" to JsonArray(emptyList()), "SecurityOpt" to JsonArray(listOf(JsonPrimitive("seccomp=unconfined"))),
        )) {
            val altered = JsonObject(observed + ("HostConfig" to JsonObject(observed.getValue("HostConfig").jsonObject + (key to value))))
            assertThrows(IllegalArgumentException::class.java, { spec.validateInspection(altered, resource, audit.imageId, command) }, key)
        }
        assertThrows(IllegalArgumentException::class.java) {
            spec.validateInspection(JsonObject(observed + ("Image" to JsonPrimitive("sha256:" + "f".repeat(64)))), resource, audit.imageId, command)
        }
        val artifact = ArtifactMetadata("fixture", "fixture.apk", 1, "b".repeat(64), "", "", 0)
        val wrapper = WrapperVerification(recipe.gradleVersion, "https://services.gradle.org/distributions/gradle-8.14.3-bin.zip",
            "c".repeat(64), "SUPPLIED_BY_RUNNER", recipe.wrapperJarGradleVersion, "d".repeat(64))
        val manifest = BuildEnvironmentManifest(
            generatedAt = "fixture", jobId = jobId, recipeId = recipe.id, repositoryUrl = recipe.repositoryUrl,
            requestedRevision = recipe.revision, resolvedCommitSha = "a".repeat(40), buildRoot = recipe.buildRoot,
            tasks = recipe.tasks, gradleVersion = recipe.gradleVersion, javaVersion = audit.java.version, javaVendor = audit.java.vendor,
            operatingSystem = audit.operatingSystem, androidSdk = audit.mounts.single { it.destination == profile.sdk }.source,
            androidSdkApiLevel = 36, buildToolsVersion = "36.0.0",
            determinism = recipe.determinism, wrapper = wrapper, dependencies = emptyList(),
            artifacts = listOf(ManifestFile("outputs/fixture.apk", 1, artifact.sha256)), sandbox = audit.sandbox,
            controllerJava = PublicJavaRuntime("21", "fixture controller"), controllerOperatingSystem = "fixture controller", dockerAudit = audit,
        )
        val manifestPath = Files.createDirectories(state.resolve("manifests/$jobId")).resolve("reprodroid-build.json")
        val json = Json { encodeDefaults = true; explicitNulls = false }
        Files.writeString(manifestPath, json.encodeToString(manifest))
        store.recordBuildManifest(jobId, "manifests/$jobId/reprodroid-build.json", sha256(manifestPath))
        assertTrue(store.completeRealSuccessIfActive(jobId, listOf(StoredArtifact(artifact, null))))
        val publisher = BuildManifestPublisher(store, state)
        val publicManifest = publisher.publicManifest(jobId)
        assertEquals(3, publicManifest.schemaVersion)
        assertEquals(audit.sandbox, publicManifest.sandbox)
        val publicJson = json.encodeToString(publicManifest)
        assertFalse(publicJson.contains(state.toString()))
        assertFalse(publicJson.contains(audit.ownerId))
        assertFalse(publicJson.contains(audit.buildContainerId))
        val invalidManifests = mapOf(
            "exit" to manifest.copy(dockerAudit = audit.copy(exitCode = 137)),
            "oom" to manifest.copy(dockerAudit = audit.copy(oomKilled = true)),
            "owner" to manifest.copy(dockerAudit = audit.copy(ownerId = "other-owner")),
            "engine" to manifest.copy(dockerAudit = audit.copy(engineId = "other-engine")),
            "snapshot" to manifest.copy(dockerAudit = audit.copy(snapshotSha256 = "f".repeat(64))),
            "container" to manifest.copy(dockerAudit = audit.copy(buildContainerId = "f".repeat(64))),
            "attempt" to manifest.copy(dockerAudit = audit.copy(preflightAttemptId = audit.buildAttemptId)),
            "observation" to manifest.copy(dockerAudit = audit.copy(preflightOutput = "replacement")),
            "java" to manifest.copy(javaVersion = "21"),
            "os" to manifest.copy(operatingSystem = "controller OS"),
            "controller" to manifest.copy(controllerJava = null),
            "missing sandbox" to manifest.copy(sandbox = null),
            "wrong mode" to manifest.copy(sandbox = SandboxEvidence(BuildSandboxMode.HOST)),
            "downgrade" to manifest.copy(schemaVersion = 3, sandbox = null, dockerAudit = null,
                controllerJava = null, controllerOperatingSystem = null),
            "build root" to manifest.copy(buildRoot = "other"),
            "tasks" to manifest.copy(tasks = listOf("otherTask")),
            "SDK" to manifest.copy(androidSdkApiLevel = 35),
            "SDK source" to manifest.copy(androidSdk = "/wrong-sdk"),
            "build tools" to manifest.copy(buildToolsVersion = "35.0.0"),
            "Gradle" to manifest.copy(gradleVersion = "8.12", wrapper = wrapper.copy(gradleVersion = "8.12")),
            "artifact size" to manifest.copy(artifacts = listOf(manifest.artifacts.single().copy(sizeBytes = 2))),
        )
        for ((label, invalid) in invalidManifests) {
            Files.writeString(manifestPath, json.encodeToString(invalid))
            // A matching file checksum is necessary but never sufficient for a valid execution record.
            store.recordBuildManifest(jobId, "manifests/$jobId/reprodroid-build.json", sha256(manifestPath))
            assertEquals("BUILD_MANIFEST_INVALID", assertThrows(ApiException::class.java,
                { publisher.publicManifest(jobId) }, label).code, label)
        }
        Files.writeString(manifestPath, json.encodeToString(manifest))
        store.recordBuildManifest(jobId, "manifests/$jobId/reprodroid-build.json", sha256(manifestPath))
        sql("UPDATE sandbox_resources SET removed=0 WHERE role='BUILD'")
        assertEquals("BUILD_MANIFEST_INVALID", assertThrows(ApiException::class.java) { publisher.publicManifest(jobId) }.code)
        sql("UPDATE sandbox_resources SET removed=1 WHERE role='BUILD'")
        assertEquals(publicManifest, publisher.publicManifest(jobId))
    }

    private var forceTimeout = false

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:${state.resolve("reprodroid-runner.sqlite3")}").use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }
}
