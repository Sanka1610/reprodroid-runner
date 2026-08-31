package com.sanka1610.reprodroid.runner

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

internal enum class SandboxResourceRole { PREFLIGHT, BUILD }

internal data class SandboxResource(
    val attemptId: String,
    val jobId: String,
    val ownerId: String,
    val role: SandboxResourceRole,
    val expectedName: String,
    val engineId: String,
    val endpoint: String,
    val containerId: String? = null,
    val removed: Boolean = false,
    val observationJson: String? = null,
    val buildFailureCode: String? = null,
    val cleanupFailureCode: String? = null,
) {
    val labels: Map<String, String> get() = mapOf(
        "com.reprodroid.owner" to ownerId, "com.reprodroid.job" to jobId,
        "com.reprodroid.attempt" to attemptId, "com.reprodroid.role" to role.name,
    )

    fun validate() {
        listOf(attemptId, jobId, ownerId).forEach { require(UUID.fromString(it).toString() == it) }
        require(expectedName == "reprodroid-${role.name.lowercase()}-$attemptId")
        require(engineId.isNotBlank() && engineId.length <= 256 && engineId.none(Char::isISOControl))
        require(endpoint == DockerSandboxProfile().endpoint)
        require(containerId == null || containerId.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Held across recovery, execution and bounded shutdown cleanup, not just while the JVM starts. */
internal class SandboxStateLease(stateDirectory: Path) : Closeable {
    private val channel = FileChannel.open(
        stateDirectory.resolve("sandbox-controller.lock"), StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
    )
    private val lock = try {
        requireNotNull(channel.tryLock()) { "Runner state directory is already in use." }
    } catch (failure: Throwable) {
        channel.close()
        throw failure
    }

    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }
}

internal data class SandboxContainerIdentity(val id: String, val name: String, val labels: Map<String, String>)

/** Recovery operations are deliberately narrower than build creation. Implementations must bound every call. */
internal interface SandboxRecoveryEngine {
    suspend fun identity(endpoint: String): String
    suspend fun ownedContainers(endpoint: String, ownerId: String): List<SandboxContainerIdentity>
    suspend fun remove(endpoint: String, containerId: String)
}

internal class SandboxLifecycle(
    private val store: SQLiteJobStore,
    private val engine: SandboxRecoveryEngine,
    val ownerId: String,
) {
    @Volatile var cleanupPending: Boolean = store.sandboxResources().isNotEmpty()
        private set

    suspend fun recover(): Boolean = withContext(NonCancellable) {
        try {
            withTimeout(60_000) {
                val resources = store.sandboxResources()
                if (resources.isEmpty()) return@withTimeout
                // Inspect even completed intents: an unexpected surviving owned object requires operator review.
                for ((endpoint, endpointResources) in resources.groupBy { it.endpoint }) {
                    val actualEngine = engine.identity(endpoint)
                    require(endpointResources.all { it.ownerId == ownerId && it.engineId == actualEngine })
                    val containers = engine.ownedContainers(endpoint, ownerId)
                    require(containers.all { container -> endpointResources.any { matches(it, container) && !it.removed } })
                    for (resource in endpointResources.filterNot { it.removed }) {
                        val matching = containers.filter { matches(resource, it) }
                        require(matching.size <= 1)
                        matching.singleOrNull()?.let { container ->
                            // Persist a recovered create-before-ID result before changing the resource.
                            store.recordSandboxContainerId(resource.attemptId, container.id)
                            engine.remove(endpoint, container.id)
                        }
                        require(engine.identity(endpoint) == resource.engineId)
                        val remaining = engine.ownedContainers(endpoint, ownerId)
                        require(remaining.none { matches(resource, it) || it.name == resource.expectedName || it.id == resource.containerId })
                        store.recordSandboxRemoved(resource.attemptId)
                    }
                }
            }
            cleanupPending = false
            true
        } catch (_: Exception) {
            cleanupPending = true
            false
        }
    }

    fun intent(jobId: String, role: SandboxResourceRole, engineId: String): SandboxResource {
        check(!cleanupPending)
        val attempt = UUID.randomUUID().toString()
        val resource = SandboxResource(
            attempt, jobId, ownerId, role, "reprodroid-${role.name.lowercase()}-$attempt",
            engineId, DockerSandboxProfile().endpoint,
        )
        try {
            store.recordSandboxIntent(resource)
        } catch (failure: Throwable) {
            cleanupPending = true
            throw failure
        }
        return resource
    }

    fun auditFailure() { cleanupPending = true }

    private fun matches(resource: SandboxResource, container: SandboxContainerIdentity): Boolean =
        container.id.matches(Regex("[0-9a-f]{64}")) &&
            (resource.containerId == null || resource.containerId == container.id) &&
            container.name == resource.expectedName && resource.labels.all { (key, value) -> container.labels[key] == value }
}
