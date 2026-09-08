package com.sanka1610.reprodroid.runner

import io.netty.channel.ChannelPipeline
import io.netty.handler.ssl.SslHandler
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Replaces the leaf for new TLS connections without restarting domain workers or active Jobs. */
internal class RenewingTlsContext(
    private val ca: LocalCertificateAuthority,
    private val security: RunnerSecurityStore,
    private val endpointHost: String,
    initial: TlsMaterial,
) : Closeable {
    private data class Published(val generation: String, val context: SSLContext, val expiresAt: Instant)
    private val published = AtomicReference<Published?>(Published(initial.generationId, context(initial), initial.leaf.notAfter.toInstant()))
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "runner-certificate-renewal").apply { isDaemon = true }
    }

    init {
        executor.scheduleWithFixedDelay({
            try {
                refresh()
            } catch (failure: Exception) {
                if (failure is IllegalStateException && failure.message?.startsWith("SECURITY_PUBLICATION_BUSY:") == true) {
                    return@scheduleWithFixedDelay
                }
                // Never keep accepting new TLS connections with missing or conflicting durable material.
                published.set(null)
                LoggerFactory.getLogger("ReproDroidRunner").error("Runner TLS material is unavailable; new connections are refused.")
            }
        }, 1, 1, TimeUnit.MINUTES)
    }

    @Synchronized
    internal fun refresh() {
        val material = ca.loadOrRenew(endpointHost, { security.recordCertificates(it) }, security::verifyCertificates)
        security.verifyCertificates(material)
        if (published.get()?.generation != material.generationId) {
            published.set(Published(material.generationId, context(material), material.leaf.notAfter.toInstant()))
        }
    }

    fun configure(pipeline: ChannelPipeline) {
        val active = published.get() ?: error("SECURITY_MATERIAL_UNAVAILABLE")
        check(active.expiresAt.isAfter(Instant.now())) { "SECURITY_LEAF_EXPIRED" }
        val engine = active.context.createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
            needClientAuth = false
        }
        // Ktor 3.5 HTTP/1.1 runs this hook during channel initialization, before any TLS bytes.
        // The application keeps an SSL connector and refuses a pipeline without its initial SSL gate.
        check(pipeline.get(SslHandler::class.java) != null) { "SECURITY_SSL_CONNECTOR_REQUIRED" }
        pipeline.replace(SslHandler::class.java, "ssl", SslHandler(engine))
    }

    override fun close() {
        executor.shutdownNow()
        published.set(null)
    }

    private fun context(material: TlsMaterial): SSLContext {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(material.keyStore, material.password)
        }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    }
}
