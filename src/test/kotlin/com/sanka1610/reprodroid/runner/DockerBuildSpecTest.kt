package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

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

    private fun fixture(): DockerBuildSpec {
        // Only omit the real 16 GiB check for path tests in JUnit's possibly-small tmpfs.
        val profile = DockerSandboxProfile(minimumFreeDiskBytes = 0)
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
