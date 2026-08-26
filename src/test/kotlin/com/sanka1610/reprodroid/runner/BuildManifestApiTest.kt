package com.sanka1610.reprodroid.runner

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BuildManifestApiTest {
    @TempDir
    lateinit var stateDirectory: Path

    @Test
    fun `returns redacted public manifest for succeeded real job in deterministic order`() = testApplication {
        val seeded = seedManifest { manifest ->
            manifest.copy(
                dependencies = listOf(
                    ManifestFile("group/zeta-2.jar", 2, "b".repeat(64)),
                    ManifestFile("other/alpha-1.jar", 1, "a".repeat(64)),
                    ManifestFile("group/alpha-1.jar", 1, "a".repeat(64)),
                ),
            )
        }
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")

        assertEquals(HttpStatusCode.OK, response.status)
        val rawResponse = response.bodyAsText()
        val projection = API_JSON.decodeFromString<BuildEnvironmentManifestResponse>(rawResponse)
        assertEquals(1, projection.schemaVersion)
        assertEquals(COMMIT, projection.commit)
        assertEquals(36, projection.androidSdk)
        assertEquals("36.0.0", projection.buildTools)
        assertEquals(
            listOf("alpha-1.jar", "alpha-1.jar", "zeta-2.jar"),
            projection.dependencies.map(PublicBuildDependency::fileName),
        )
        assertFalse(rawResponse.contains(stateDirectory.toString()))
        assertFalse(rawResponse.contains("repositoryUrl"))
        assertFalse(rawResponse.contains("buildRoot"))
        assertFalse(rawResponse.contains("operatingSystem"))
        assertFalse(rawResponse.contains("group/"))
    }

    @Test
    fun `returns 404 when job does not exist`() = testApplication {
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/missing/build-environment-manifest")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("JOB_NOT_FOUND", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 409 when manifest is not ready`() = testApplication {
        val store = SQLiteJobStore(stateDirectory)
        val jobId = store.createJob(realRequest()).jobId
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/$jobId/build-environment-manifest")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("BUILD_MANIFEST_NOT_READY", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 410 for internal schema v1`() = testApplication {
        val seeded = seedManifest { it.copy(schemaVersion = 1) }
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.Gone, response.status)
        assertEquals("BUILD_MANIFEST_REDACTED_EMPTY", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 410 without echoing a sensitive dependency file name`() = testApplication {
        val sensitiveName = "github_pat_secret-value.jar"
        val seeded = seedManifest { manifest ->
            manifest.copy(dependencies = listOf(ManifestFile("group/$sensitiveName", 1, "a".repeat(64))))
        }
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.Gone, response.status)
        val rawResponse = response.bodyAsText()
        assertEquals("BUILD_MANIFEST_REDACTION_REQUIRED", API_JSON.decodeFromString<ApiErrorResponse>(rawResponse).code)
        assertFalse(rawResponse.contains(sensitiveName))
    }

    @Test
    fun `returns 409 when dependency file name exceeds its UTF 8 limit`() = testApplication {
        val seeded = seedManifest { manifest ->
            manifest.copy(dependencies = listOf(ManifestFile("group/${"a".repeat(256)}", 1, "a".repeat(64))))
        }
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("BUILD_MANIFEST_PUBLICATION_LIMIT_EXCEEDED", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 500 when manifest SHA 256 no longer matches the audit record`() = testApplication {
        val seeded = seedManifest()
        Files.writeString(seeded.path, "tampered")
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("BUILD_MANIFEST_INVALID", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 500 for malformed JSON with a matching audit hash`() = testApplication {
        val seeded = seedManifest(rawJson = "{not-json")
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("BUILD_MANIFEST_INVALID", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 500 when manifest path escapes the job manifest directory`() = testApplication {
        val seeded = seedManifest(relativePath = "outside.json")
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("BUILD_MANIFEST_INVALID", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 500 when manifest path is a symbolic link`() = testApplication {
        val seeded = seedManifest()
        val original = stateDirectory.resolve("original.json")
        Files.move(seeded.path, original)
        Files.createSymbolicLink(seeded.path, original)
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("BUILD_MANIFEST_INVALID", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `returns 500 when a public SHA field is malformed`() = testApplication {
        val seeded = seedManifest { manifest ->
            manifest.copy(dependencies = listOf(ManifestFile("group/example.jar", 1, "A".repeat(64))))
        }
        application { runnerModule(testConfig()) }
        val response = client.get("/v1/jobs/${seeded.jobId}/build-environment-manifest")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("BUILD_MANIFEST_INVALID", response.decode<ApiErrorResponse>().code)
    }

    @Test
    fun `enforces dependency private file and serialized response publication limits`() {
        val twoDependencies = seedManifest { manifest ->
            manifest.copy(
                dependencies = listOf(
                    ManifestFile("group/a.jar", 1, "a".repeat(64)),
                    ManifestFile("group/b.jar", 1, "b".repeat(64)),
                ),
            )
        }
        assertPublicationLimit(
            BuildManifestPublisher(
                SQLiteJobStore(stateDirectory),
                stateDirectory,
                BuildManifestPublicationLimits(maxPublicDependencies = 1),
            ),
            twoDependencies.jobId,
        )
        assertPublicationLimit(
            BuildManifestPublisher(
                SQLiteJobStore(stateDirectory),
                stateDirectory,
                BuildManifestPublicationLimits(maxPrivateManifestBytes = 1),
            ),
            twoDependencies.jobId,
        )
        assertPublicationLimit(
            BuildManifestPublisher(
                SQLiteJobStore(stateDirectory),
                stateDirectory,
                BuildManifestPublicationLimits(maxPublicResponseBytes = 1),
            ),
            twoDependencies.jobId,
        )
    }

    private fun assertPublicationLimit(publisher: BuildManifestPublisher, jobId: String) {
        val failure = assertThrows(ApiException::class.java) { publisher.publicManifest(jobId) }
        assertEquals(HttpStatusCode.Conflict, failure.status)
        assertEquals("BUILD_MANIFEST_PUBLICATION_LIMIT_EXCEEDED", failure.code)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = API_JSON.decodeFromString(bodyAsText())

    private fun seedManifest(
        rawJson: String? = null,
        relativePath: String? = null,
        transform: (BuildEnvironmentManifest) -> BuildEnvironmentManifest = { it },
    ): SeededManifest {
        val store = SQLiteJobStore(stateDirectory)
        val recipe = releaseRecipe()
        val jobId = store.createJob(realRequest()).jobId
        assertTrue(store.beginResolvingRealJob(jobId, recipe))
        assertTrue(store.awaitRealConfirmation(jobId, COMMIT))
        assertTrue(store.confirmRealJob(jobId, COMMIT))
        val artifact = ArtifactMetadata(
            artifactId = "22222222-2222-2222-2222-222222222222",
            fileName = "microg.apk",
            sizeBytes = 100,
            sha256 = APK_SHA,
            packageName = "",
            versionName = "",
            versionCode = 0,
        )
        assertTrue(store.completeRealSuccessIfActive(jobId, listOf(StoredArtifact(artifact, null))))
        val manifest = transform(
            BuildEnvironmentManifest(
                generatedAt = "2026-08-27T00:00:00Z",
                jobId = jobId,
                recipeId = recipe.id,
                repositoryUrl = recipe.repositoryUrl,
                requestedRevision = recipe.revision,
                resolvedCommitSha = COMMIT,
                buildRoot = recipe.buildRoot,
                tasks = recipe.tasks,
                gradleVersion = recipe.gradleVersion,
                javaVersion = "18.0.2.1+1",
                javaVendor = "Eclipse Adoptium",
                operatingSystem = "private operating system value",
                androidSdk = "/private/android/sdk",
                androidSdkApiLevel = recipe.androidSdkApiLevel,
                buildToolsVersion = recipe.buildToolsVersion,
                wrapper = WrapperVerification(
                    gradleVersion = recipe.gradleVersion,
                    distributionUrl = "https://services.gradle.org/distributions/gradle-8.14.3-bin.zip",
                    distributionSha256 = "c".repeat(64),
                    distributionChecksumSource = "SUPPLIED_BY_RUNNER",
                    wrapperJarGradleVersion = recipe.wrapperJarGradleVersion,
                    wrapperJarSha256 = "d".repeat(64),
                ),
                dependencies = listOf(ManifestFile("group/example.jar", 1, "a".repeat(64))),
                artifacts = listOf(ManifestFile("outputs/microg.apk", 100, APK_SHA)),
            ),
        )
        val auditRelativePath = relativePath ?: "manifests/$jobId/reprodroid-build.json"
        val path = stateDirectory.resolve(auditRelativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, rawJson ?: PRIVATE_JSON.encodeToString(manifest))
        assertTrue(store.recordBuildManifest(jobId, auditRelativePath, sha256(path)))
        return SeededManifest(jobId, path)
    }

    private fun realRequest() = CreateJobRequest(
        executionMode = ExecutionMode.REAL_TRUSTED,
        repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
        revision = RequestedRevision(RevisionType.TAG, "6.1.4"),
    )

    private fun releaseRecipe() = BuildRecipeRegistry.defaultRecipes.single { it.revision == realRequest().revision }

    private fun testConfig() = RunnerConfig(
        host = "127.0.0.1",
        port = 8080,
        stateDirectory = stateDirectory,
        simulationStepDelayMillis = 1,
    )

    private data class SeededManifest(val jobId: String, val path: Path)

    private companion object {
        const val COMMIT = "d8df10ab687a1c1ca05221634cfa46bad262023a"
        const val APK_SHA = "30de03caea3da52c9febbeebb5d7f0d3246811d288d81b522bb456da19e7b033"
        val PRIVATE_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val API_JSON = Json { ignoreUnknownKeys = false }
    }
}
