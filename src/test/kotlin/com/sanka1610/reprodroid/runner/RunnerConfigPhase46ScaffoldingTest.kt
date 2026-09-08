package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Executable checks for the fail-closed configuration surface that exists today. */
class RunnerConfigPhase46ScaffoldingTest {
    @Test
    fun unsetConfigurationRetainsLoopbackDevelopmentDefaults() {
        val config = RunnerConfig.fromEnvironment(emptyMap())

        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertFalse(config.apiV2Enabled)
        assertFalse(config.realBuildEnabled)
        assertEquals(TransportMode.DEVELOPMENT_HTTP, config.transportMode)
        assertEquals(null, config.advertisedEndpoint)
    }

    @Test
    fun malformedOrNonLoopbackConfigurationFailsClosed() {
        listOf(
            mapOf("REPRODROID_HOST" to ""),
            mapOf("REPRODROID_HOST" to "192.0.2.10"),
            mapOf("REPRODROID_HOST" to "192.0.2.10", "REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK" to "yes"),
            mapOf("REPRODROID_PORT" to "0"),
            mapOf("REPRODROID_PORT" to "65536"),
            mapOf("REPRODROID_PORT" to "invalid"),
            mapOf("REPRODROID_STATE_DIR" to " "),
        ).forEach { environment ->
            assertThrows(IllegalArgumentException::class.java) {
                RunnerConfig.fromEnvironment(environment)
            }
        }
    }

    @Test
    fun apiV2CannotUseNonLoopbackEvenWhenLegacyRiskAcknowledgementIsPresent() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            RunnerConfig.fromEnvironment(
                mapOf(
                    "REPRODROID_HOST" to "192.0.2.10",
                    "REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK" to "true",
                    "REPRODROID_ENABLE_API_V2" to "true",
                ),
            )
        }

        assertTrue(requireNotNull(failure.message).contains("REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK"))
    }

    @Test
    fun unknownAndEmptyTransportModeNeverChooseAConnector() {
        listOf("", "paired_https", "PAIRED_HTTPS ", "UNKNOWN").forEach { mode ->
            assertThrows(IllegalArgumentException::class.java) {
                RunnerConfig.fromEnvironment(mapOf("REPRODROID_TRANSPORT_MODE" to mode))
            }
        }
    }

    @Test
    fun pairedModeRequiresAnExactHttpsOriginAndEnablesAuthenticatedV2() {
        val base = mapOf("REPRODROID_TRANSPORT_MODE" to "PAIRED_HTTPS", "REPRODROID_HOST" to "127.0.0.1")
        val config = RunnerConfig.fromEnvironment(base + ("REPRODROID_ADVERTISED_ENDPOINT" to "https://127.0.0.1:8443"))
        assertEquals(8443, config.port)
        assertEquals(TransportMode.PAIRED_HTTPS, config.transportMode)
        assertEquals("https://127.0.0.1:8443", config.advertisedEndpoint.toString())
        assertTrue(config.apiV2Enabled)
        listOf("", "http://127.0.0.1:8443", "https://127.0.0.1", "https://127.0.0.1:443",
            "https://user:password@127.0.0.1:8443", "https://127.0.0.1:8443/not-root",
            "https://127.0.0.1:8443?query=1", "https://127.0.0.1:8443#fragment",
            "https://0.0.0.0:8443", "https://[::]:8443").forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) {
                RunnerConfig.fromEnvironment(base + ("REPRODROID_ADVERTISED_ENDPOINT" to origin))
            }
        }
        listOf("0.0.0.0", "::", "0:0:0:0:0:0:0:0", "0").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) {
                RunnerConfig.fromEnvironment(base + mapOf("REPRODROID_HOST" to host, "REPRODROID_ADVERTISED_ENDPOINT" to "https://127.0.0.1:8443"))
            }
        }
    }
}
