package com.sanka1610.reprodroid.runner

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
    val dependencyPinning: DependencyPinning = DependencyPinning.LOCKFILE_OFFLINE,
    val determinism: DeterminismOptions = DeterminismOptions(noBuildCache = false),
    val managedToolchains: Boolean = false,
    val discoveryTimeout: Duration? = null,
)

internal class BuildRecipeRegistry(
    recipes: List<BuildRecipe> = defaultRecipes,
) {
    init {
        recipes.forEach(::validateDeterminismRecipe)
    }

    private val recipesByRepository = recipes
        .map { it.copy(repositoryUrl = canonicalRepositoryKey(it.repositoryUrl)) }
        .groupBy { it.repositoryUrl }

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
                dependencyPinning = DependencyPinning.NONE,
                determinism = DeterminismOptions(noBuildCache = false),
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
                dependencyPinning = DependencyPinning.NONE,
                determinism = DeterminismOptions(noBuildCache = false),
            ),
        )

        internal fun canonicalRepositoryKey(repositoryUrl: String): String =
            SourceProviderHostRegistry.canonicalUrl(repositoryUrl)

        private fun validateDeterminismRecipe(recipe: BuildRecipe) {
            require(recipe.determinism.sourceDateEpoch?.let { it >= 0 } != false) {
                "source_date_epoch must be a non-negative Unix seconds literal."
            }
            require(recipe.tasks.none { it == "--build-cache" || it == "--no-build-cache" }) {
                "Gradle build-cache options are reserved for the determinism recipe block."
            }
        }
    }
}
