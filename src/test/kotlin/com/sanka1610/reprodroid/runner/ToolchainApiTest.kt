package com.sanka1610.reprodroid.runner

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ToolchainApiTest {
    @TempDir lateinit var stateDirectory: Path

    @Test
    fun `bundled catalog resolves the phase 4 baseline without side effects`() = testApplication {
        application { runnerModule(config()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) } }
        val catalog = client.get("/v2/toolchains/catalog").body<ToolchainCatalogResponse>()
        assertEquals("linux-x86_64", catalog.platform)
        assertEquals(9, catalog.artifacts.size)
        assertTrue(catalog.catalogSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(catalog.artifacts.all { it.archiveSha256.matches(Regex("[0-9a-f]{64}")) && it.archiveSha256.toSet() != setOf('0') })
        assertEquals(
            "android/platforms/android-37.0",
            catalog.artifacts.single { it.artifactId == "android-platform-37.0-r02" }.installSubdirectory,
        )
        assertEquals(
            AndroidLocalPackageMetadata(
                path = "platforms;android-37.0",
                apiLevel = "37.0",
                revisionMajor = 2,
                extensionLevel = 22,
                baseExtension = true,
                codename = "",
                layoutlibApi = 15,
                displayName = "Android SDK Platform 37.0",
            ),
            catalog.artifacts.single { it.artifactId == "android-platform-37.0-r02" }.androidLocalPackage,
        )

        val response = client.post("/v2/toolchains/plans:resolve") {
            contentType(ContentType.Application.Json)
            setBody(ResolveToolchainPlanRequest(BASELINE))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val plan = response.body<ToolchainPlanResponse>()
        assertEquals(5, plan.items.size)
        assertEquals(setOf("android-sdk-license", "gradle-apache-2.0", "temurin-gpl2-classpath"), plan.requiredLicenses.map { it.licenseId }.toSet())
        assertTrue(plan.downloadBytes.toLong() > 0)
    }

    @Test
    fun `install rejects stale plan and missing current license consent before download`() = testApplication {
        application { runnerModule(config()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) } }
        val response = client.post("/v2/toolchains/installations") {
            contentType(ContentType.Application.Json)
            headers.append("X-ReproDroid-Contract", "toolchain-install@1")
            headers.append("Idempotency-Key", "123e4567-e89b-12d3-a456-426614174000")
            setBody(CreateToolchainInstallationRequest("0".repeat(64), "0".repeat(64), BASELINE, emptyList()))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("TOOLCHAIN_PLAN_STALE", response.body<ApiErrorResponse>().code)
    }

    @Test
    fun `toolchain requests reject duplicate keys unknown fields and non uuid idempotency keys`() = testApplication {
        application { runnerModule(config()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) } }

        val duplicate = client.post("/v2/toolchains/plans:resolve") {
            contentType(ContentType.Application.Json)
            setBody("""{"requirements":[],"requirements":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)
        assertEquals("INVALID_JSON", duplicate.body<ApiErrorResponse>().code)

        val unknown = client.post("/v2/toolchains/plans:resolve") {
            contentType(ContentType.Application.Json)
            setBody("""{"requirements":[],"extra":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("INVALID_REQUEST", unknown.body<ApiErrorResponse>().code)

        val invalidKey = client.post("/v2/toolchains/installations") {
            contentType(ContentType.Application.Json)
            headers.append("X-ReproDroid-Contract", "toolchain-install@1")
            headers.append("Idempotency-Key", "not-a-uuid")
            setBody(CreateToolchainInstallationRequest("0".repeat(64), "0".repeat(64), BASELINE, emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidKey.status)
        assertEquals("INVALID_REQUEST", invalidKey.body<ApiErrorResponse>().code)
    }

    private fun config() = RunnerConfig("127.0.0.1", 8080, stateDirectory, simulationStepDelayMillis = 0, apiV2Enabled = true)

    companion object {
        private val BASELINE = listOf(
            ToolchainRequirement(ToolchainComponent.JDK, "21.0.12+1"),
            ToolchainRequirement(ToolchainComponent.GRADLE, "8.14.3"),
            ToolchainRequirement(ToolchainComponent.ANDROID_COMMAND_LINE_TOOLS, "15859902"),
            ToolchainRequirement(ToolchainComponent.ANDROID_PLATFORM, "36-r02"),
            ToolchainRequirement(ToolchainComponent.ANDROID_BUILD_TOOLS, "36.0.0"),
        )
    }
}
