package com.sanka1610.reprodroid.runner

import java.net.URI
import java.time.Duration

internal data class BuildRecipe(
    val id: String,
    val repositoryUrl: String,
    val revision: RequestedRevision,
    val variantName: String,
    val buildRoot: String,
    val javaMajor: Int,
    val javaHomeEnvironmentVariable: String? = null,
    val gradleVersion: String,
    val androidSdkApiLevel: Int,
    val buildToolsVersion: String,
    val distributionType: String,
    val wrapperJarGradleVersion: String,
    val tasks: List<String>,
    val artifactPatterns: List<String>,
    val requireSingleApk: Boolean,
    val timeout: Duration,
    val allowRunnerSuppliedDistributionChecksum: Boolean,
)

internal class BuildRecipeRegistry(
    recipes: List<BuildRecipe> = defaultRecipes,
) {
    private val recipesByRepository = recipes.groupBy { canonicalRepositoryKey(it.repositoryUrl) }

    fun requireAllowed(repositoryUrl: String, revision: RequestedRevision): BuildRecipe {
        val repositoryRecipes = recipesByRepository[canonicalRepositoryKey(repositoryUrl)]
            ?: throw ApiException.forbidden(
                code = "REPOSITORY_NOT_ALLOWLISTED",
                message = "Real build is not permitted for this repository.",
            )
        return repositoryRecipes.singleOrNull { it.revision == revision }
            ?: throw ApiException.forbidden(
                code = "REVISION_NOT_ALLOWLISTED",
                message = "The requested ref is not permitted by the repository build recipe.",
            )
    }

    fun find(repositoryUrl: String, revision: RequestedRevision): BuildRecipe? =
        recipesByRepository[canonicalRepositoryKey(repositoryUrl)]?.singleOrNull { it.revision == revision }

    companion object {
        val defaultRecipes = listOf(
            BuildRecipe(
                id = "morpheapp-microg-re-main-debug",
                repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
                revision = RequestedRevision(RevisionType.BRANCH, "main"),
                variantName = "defaultDebug",
                buildRoot = ".",
                javaMajor = 21,
                gradleVersion = "8.14.3",
                androidSdkApiLevel = 36,
                buildToolsVersion = "36.0.0",
                distributionType = "bin",
                wrapperJarGradleVersion = "8.11.1",
                tasks = listOf(":play-services-core:assembleDefaultDebug"),
                artifactPatterns = listOf(
                    "play-services-core/build/outputs/apk/default/debug/*.apk",
                ),
                requireSingleApk = true,
                timeout = Duration.ofMinutes(30),
                allowRunnerSuppliedDistributionChecksum = true,
            ),
            BuildRecipe(
                id = "morpheapp-microg-re-6.1.4-default-release",
                repositoryUrl = "https://github.com/MorpheApp/MicroG-RE.git",
                revision = RequestedRevision(RevisionType.TAG, "6.1.4"),
                variantName = "defaultRelease",
                buildRoot = ".",
                javaMajor = 18,
                javaHomeEnvironmentVariable = "REPRODROID_JDK_18_HOME",
                gradleVersion = "8.14.3",
                androidSdkApiLevel = 36,
                buildToolsVersion = "36.0.0",
                distributionType = "bin",
                wrapperJarGradleVersion = "8.11.1",
                tasks = listOf("clean", ":play-services-core:assembleDefaultRelease"),
                artifactPatterns = listOf(
                    "play-services-core/build/outputs/apk/default/release/*.apk",
                ),
                requireSingleApk = true,
                timeout = Duration.ofMinutes(30),
                allowRunnerSuppliedDistributionChecksum = true,
            ),
        )

        internal fun canonicalRepositoryKey(repositoryUrl: String): String {
            val uri = runCatching { URI(repositoryUrl) }.getOrNull()
                ?: throw ApiException.badRequest("INVALID_REPOSITORY_URL", "repositoryUrl is not a valid URI.")
            if (
                uri.scheme?.lowercase() != "https" ||
                uri.host?.lowercase() != "github.com" ||
                uri.userInfo != null ||
                uri.port != -1 ||
                uri.query != null ||
                uri.fragment != null
            ) {
                throw ApiException.badRequest(
                    "INVALID_REPOSITORY_URL",
                    "REAL_TRUSTED repositoryUrl must be a GitHub HTTPS URL without credentials, port, query, or fragment.",
                )
            }
            val segments = uri.path.trim('/').removeSuffix(".git").split('/')
            if (segments.size != 2 || segments.any { it.isBlank() || !GITHUB_SEGMENT.matches(it) }) {
                throw ApiException.badRequest(
                    "INVALID_REPOSITORY_URL",
                    "REAL_TRUSTED repositoryUrl must identify one GitHub owner and repository.",
                )
            }
            return "https://github.com/${segments[0].lowercase()}/${segments[1].lowercase()}"
        }

        private val GITHUB_SEGMENT = Regex("[A-Za-z0-9_.-]+")
    }
}
