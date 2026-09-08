package com.sanka1610.reprodroid.runner

import java.net.URI
import java.util.Locale

/**
 * The set of source providers that a trusted build may clone from.
 *
 * This is intentionally a closed registry.  Provider metadata, release
 * discovery, and asset download remain Android responsibilities; the Runner
 * only uses the canonical URL to clone the source snapshot.
 */
internal enum class SourceProvider(val host: String) {
    GITHUB("github.com"),
    CODEBERG("codeberg.org"),
}

internal data class CanonicalRepositoryUrl(
    val provider: SourceProvider,
    val value: String,
)

internal object SourceProviderHostRegistry {
    private val providersByHost = SourceProvider.entries.associateBy { it.host }
    private val pathSegment = Regex("[A-Za-z0-9_.-]+")

    fun canonicalize(repositoryUrl: String): CanonicalRepositoryUrl {
        val uri = runCatching { URI(repositoryUrl) }.getOrNull()
            ?: invalid("repositoryUrl is not a valid URI.")
        val host = uri.host?.lowercase(Locale.ROOT)
        val provider = providersByHost[host]
            ?: invalid("repositoryUrl must use an allowlisted public source provider.")
        val rawAuthority = uri.rawAuthority

        if (
            uri.isOpaque ||
            uri.scheme?.lowercase(Locale.ROOT) != "https" ||
            rawAuthority == null ||
            !rawAuthority.equals(provider.host, ignoreCase = true) ||
            uri.rawUserInfo != null ||
            uri.port != -1 ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) {
            invalid("repositoryUrl must be an HTTPS provider URL without credentials, port, query, or fragment.")
        }

        val rawPath = uri.rawPath
            ?.takeIf { it.startsWith("/") && it.length > 1 }
            ?: invalid("repositoryUrl must identify one provider owner and repository.")
        val path = if (rawPath.endsWith("/")) rawPath.dropLast(1) else rawPath
        if (path.endsWith("/") || path == "/") {
            invalid("repositoryUrl must identify one provider owner and repository.")
        }
        val segments = path.removePrefix("/").split('/')
        if (segments.size != 2 || segments.any { it.isEmpty() || it == "." || it == ".." || !pathSegment.matches(it) }) {
            invalid("repositoryUrl must identify one provider owner and repository without extra path segments.")
        }

        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        if (repository.isEmpty() || repository == "." || repository == ".." || !pathSegment.matches(repository)) {
            invalid("repositoryUrl must identify one provider owner and repository without encoded or traversal path segments.")
        }

        return CanonicalRepositoryUrl(
            provider = provider,
            value = "https://${provider.host}/${owner.lowercase(Locale.ROOT)}/${repository.lowercase(Locale.ROOT)}",
        )
    }

    fun canonicalUrl(repositoryUrl: String): String = canonicalize(repositoryUrl).value

    private fun invalid(message: String): Nothing =
        throw ApiException.badRequest("INVALID_REPOSITORY_URL", message)
}
