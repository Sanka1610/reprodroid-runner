package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProviderRepositoryUrlsTest {
    @Test
    fun `canonical URL accepts the two public source providers`() {
        val github = SourceProviderHostRegistry.canonicalize("https://GitHub.com/MorpheApp/MicroG-RE.git")
        assertEquals(SourceProvider.GITHUB, github.provider)
        assertEquals("https://github.com/morpheapp/microg-re", github.value)

        val codeberg = SourceProviderHostRegistry.canonicalize("https://Codeberg.org/Owner/Repository.git/")
        assertEquals(SourceProvider.CODEBERG, codeberg.provider)
        assertEquals("https://codeberg.org/owner/repository", codeberg.value)
    }

    @Test
    fun `canonical URL rejects non public or ambiguous source URLs`() {
        listOf(
            "http://github.com/owner/repository",
            "https://gitlab.com/owner/repository",
            "https://user:password@github.com/owner/repository",
            "https://github.com:443/owner/repository",
            "https://github.com/owner/repository?download=1",
            "https://github.com/owner/repository#fragment",
            "https://github.com/owner/repository/extra",
            "https://github.com/owner//repository",
            "https://github.com/owner/%2e%2e",
            "https://github.com/%2e%2e/repository",
            "https://github.com/owner/repository%2fextra",
            "https://github.com/owner/repository%5cextra",
        ).forEach { repositoryUrl ->
            val failure = assertThrows(ApiException::class.java) {
                SourceProviderHostRegistry.canonicalUrl(repositoryUrl)
            }
            assertEquals("INVALID_REPOSITORY_URL", failure.code, repositoryUrl)
        }
    }
}
