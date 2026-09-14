package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Filesystem-only policy probes; never invoke Docker or weaken an executable Job snapshot. */
class DockerBuildSpecTest {
    @TempDir lateinit var directory: Path

    @Test fun `canonical separate mounts and confined internal toolchain links are accepted`() {
        val spec = fixture()
        val jdk = Path.of(spec.mounts.single { it.destination == spec.profile.jdk }.source)
        Files.createSymbolicLink(jdk.resolve("alias"), Files.writeString(jdk.resolve("release"), "fixture"))
        spec.preflightMounts()
    }

    @Test fun `missing regular-file symlink and unnormalized mount sources fail before control`() {
        val spec = fixture()
        val source = Path.of(spec.mounts.first().source)
        val linkedAncestor = Files.createSymbolicLink(directory.resolve("workspace-alias"), source.parent)
        val invalidPaths = listOf(
            directory.resolve("missing"), Files.writeString(directory.resolve("regular"), "fixture"),
            Files.createSymbolicLink(directory.resolve("source-link"), source), linkedAncestor.resolve("source"),
            source.resolve("../source"), Path.of("relative/source"),
        )
        invalidPaths.forEach { path ->
            invalid(spec.withMount(0, spec.mounts[0].copy(source = path.toString())))
        }
    }

    @Test fun `escaping toolchain symlink writable toolchain and overlapping paths fail closed`() {
        val spec = fixture()
        invalid(spec.withMount(3, spec.mounts[3].copy(readOnly = false)))
        invalid(spec.withMount(1, spec.mounts[1].copy(source = spec.mounts[0].source)))
        invalid(spec.withMount(4, spec.mounts[4].copy(source = spec.mounts[0].source)))
        invalid(spec.withMount(0, spec.mounts[0].copy(readOnly = true)))
        val sdk = Path.of(spec.mounts[4].source)
        Files.createSymbolicLink(sdk.resolve("outside"), Files.writeString(directory.resolve("outside.txt"), "private"))
        invalid(spec)
    }

    @Test fun `insufficient free disk remains resource failure rather than mount fallback`() {
        val spec = fixture()
        val failure = assertThrows(TrustedBuildFailure::class.java) {
            DockerBuildSpec(
                (spec.profile as DockerSandboxProfile).copy(minimumFreeDiskBytes = Long.MAX_VALUE),
                spec.mounts,
                spec.environment,
            ).preflightMounts()
        }
        assertEquals("SANDBOX_RESOURCE_LIMIT_EXCEEDED", failure.code)
    }

    @Test fun `fixed Docker profile refuses even same-ID recipe changes`() {
        val profile = DockerSandboxProfile()
        val recipe = BuildRecipeRegistry.defaultRecipes.single { it.id == profile.recipeId }
        profile.requireSupported(recipe)
        listOf(recipe.copy(timeout = Duration.ofSeconds(1)), recipe.copy(tasks = listOf("otherTask")),
            recipe.copy(buildRoot = "other")).forEach { changed ->
            assertEquals("SANDBOX_PROFILE_UNSUPPORTED", assertThrows(TrustedBuildFailure::class.java) {
                profile.requireSupported(changed)
            }.code)
        }
    }

    @Test fun `generic v2 confines executable native libraries to a bounded dedicated tmpfs`() {
        val profile = GenericDockerSandboxProfile()
        assertEquals(profile.tmpfsBytes, profile.regularTmpfsBytes + profile.nativeTmpfsBytes)
        assertEquals(
            listOf(
                SandboxTmpfs("/tmp", listOf("rw", "nosuid", "nodev", "noexec", "mode=1777", "size=1006632960")),
                SandboxTmpfs("/run/reprodroid-native", listOf("rw", "nosuid", "nodev", "exec", "mode=1777", "size=67108864")),
            ),
            profile.tmpfsPolicies(),
        )
        assertEquals("-Dorg.sqlite.tmpdir=/run/reprodroid-native", profile.containerEnvironment()["JAVA_TOOL_OPTIONS"])

        val id = UUID.randomUUID().toString()
        val resource = SandboxResource(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            SandboxResourceRole.PREFLIGHT, "reprodroid-preflight-$id", "engine", profile.endpoint)
        val arguments = DockerBuildSpec(profile, emptyList(), profile.containerEnvironment())
            .createArguments(resource, listOf("/bin/true"))
        assertTrue(arguments.windowed(2).contains(listOf("--tmpfs", profile.tmpfsPolicies()[0].argument)))
        assertTrue(arguments.windowed(2).contains(listOf("--tmpfs", profile.tmpfsPolicies()[1].argument)))
        assertTrue(arguments.windowed(2).contains(listOf("--env", "JAVA_TOOL_OPTIONS=${profile.sqliteNativeJvmOption}")))
    }

    @Test fun `generic v3 adds only the detached Git shim directory to the v2 environment`() {
        val v2 = GenericDockerSandboxProfile()
        val v3 = DetachedGitGenericDockerSandboxProfile()
        assertEquals(v2.tmpfsPolicies(), v3.tmpfsPolicies())
        assertEquals(v2.containerEnvironment().filterKeys { it != "PATH" }, v3.containerEnvironment().filterKeys { it != "PATH" })
        assertEquals(
            "/opt/jdk/bin:/opt/gradle/bin:/run/reprodroid-native:/usr/bin:/bin",
            v3.containerEnvironment().getValue("PATH"),
        )
        assertEquals("/run/reprodroid-native/git", v3.detachedGitShimPath)
        assertEquals("detached-rev-parse-v1", v3.detachedGitCommandSet)
    }

    @Test fun `generic v3 build command provides only detached revision metadata`() {
        val commit = "a".repeat(40)
        val profile = DetachedGitGenericDockerSandboxProfile()
        val recipe = BuildRecipeRegistry.defaultRecipes.first().copy(tasks = listOf(":app:assembleRelease"))
        val job = StoredJob(
            jobId = "job",
            principalId = "principal",
            executionMode = ExecutionMode.REAL_TRUSTED,
            repositoryUrl = recipe.repositoryUrl,
            revision = recipe.revision,
            simulationOutcome = null,
            resolvedCommitSha = commit,
            state = JobState.BUILDING,
            sandbox = SandboxSnapshot.newGenericJob(),
            genericBuild = GenericBuildSnapshot(
                comparisonId = UUID.randomUUID().toString(),
                attempt = GenericBuildAttempt.A,
                configurationRevision = 1,
                configurationSha256 = "b".repeat(64),
                configurationCanonicalJson = "{}",
                expectedArtifactFileName = "app.apk",
            ),
        )

        val command = dockerBuildCommand(job, recipe, profile)
        assertEquals(listOf("/bin/sh", "-ec"), command.take(2))
        val script = command.single { it.contains("REPRODROID_DETACHED_GIT") }
        assertTrue(script.contains("rev-parse --abbrev-ref HEAD"))
        assertTrue(script.contains("rev-parse --verify HEAD"))
        assertTrue(script.contains("printf '%s\\n' $commit"))
        assertTrue(script.contains("*) exit 64 ;;"))
        assertTrue(script.contains("exec '/opt/jdk/bin/java'"))
        assertThrows(IllegalArgumentException::class.java) {
            dockerBuildCommand(job.copy(resolvedCommitSha = "invalid"), recipe, profile)
        }
    }

    @Test fun `historical profiles retain their exact single tmpfs and environment`() {
        listOf<DockerSandboxPolicy>(DockerSandboxProfile(), LegacyGenericDockerSandboxProfile()).forEach { profile ->
            assertEquals(
                listOf(SandboxTmpfs("/tmp", listOf("rw", "nosuid", "nodev", "size=1073741824"))),
                profile.tmpfsPolicies(),
            )
            assertFalse(profile.containerEnvironment().containsKey("JAVA_TOOL_OPTIONS"))
        }
    }

    private fun fixture(): DockerBuildSpec {
        // Model the owner of JUnit's temporary filesystem while omitting its possibly-small free-space limit.
        val profile = DockerSandboxProfile(
            uid = (Files.getAttribute(directory, "unix:uid") as Number).toInt(),
            gid = (Files.getAttribute(directory, "unix:gid") as Number).toInt(),
            minimumFreeDiskBytes = 0,
        )
        val workspace = Files.createDirectories(directory.resolve("workspace"))
        val mounts = listOf(
            workspace.resolve("source") to profile.source, workspace.resolve("home") to profile.home,
            workspace.resolve("gradle-user-home") to profile.gradleHome,
            directory.resolve("jdk") to profile.jdk, directory.resolve("sdk") to profile.sdk,
        ).mapIndexed { index, (source, destination) ->
            SandboxMount(Files.createDirectories(source).toString(), destination, index >= 3)
        }
        return DockerBuildSpec(profile, mounts, emptyMap())
    }

    private fun DockerBuildSpec.withMount(index: Int, mount: SandboxMount) =
        DockerBuildSpec(profile, mounts.mapIndexed { position, value -> if (position == index) mount else value }, environment)

    private fun invalid(spec: DockerBuildSpec) = assertEquals("SANDBOX_MOUNT_INVALID",
        assertThrows(TrustedBuildFailure::class.java) { spec.preflightMounts() }.code)
}
