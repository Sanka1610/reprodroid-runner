package com.sanka1610.reprodroid.runner

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DockerCompletionTest {
    private fun state(status: String = "exited", exit: Int = 0, oom: Boolean = false) = buildJsonObject {
        put("Status", status)
        put("StartedAt", if (status == "created") "0001-01-01T00:00:00Z" else "2026-08-31T00:00:00Z")
        put("Running", status == "running")
        put("ExitCode", exit)
        put("OOMKilled", oom)
    }

    @Test fun `CLI success alone never proves a build ran or finished`() {
        assertNull(classifyDockerBuildCompletion(state(), 0, false))
        assertEquals("SANDBOX_START_FAILED", classifyDockerBuildCompletion(state("created"), 0, false)?.code)
        assertEquals("SANDBOX_START_FAILED", classifyDockerBuildCompletion(state("created"), 125, false)?.code)
        assertEquals("GRADLE_BUILD_FAILED", classifyDockerBuildCompletion(state("running"), 0, false)?.code)
    }

    @Test fun `timeout observed OOM and generic exit remain distinct`() {
        assertEquals("GRADLE_BUILD_FAILED", classifyDockerBuildCompletion(state(exit = 137), 137, false)?.code)
        assertEquals("SANDBOX_RESOURCE_LIMIT_EXCEEDED", classifyDockerBuildCompletion(state(exit = 137, oom = true), 137, false)?.code)
        assertEquals("PROCESS_TIMEOUT", classifyDockerBuildCompletion(state("running"), null, true)?.code)
    }
}
