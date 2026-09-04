package com.sanka1610.reprodroid.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ToolchainStoreTest {
    @TempDir lateinit var stateDirectory: Path

    @Test
    fun `cancel releases reservation removes staging and does not create inventory`() {
        SQLiteJobStore(stateDirectory)
        val enteredDownload = AtomicBoolean(false)
        val installer = ToolchainInstaller(
            stateDirectory,
            archiveDownloader = ToolchainArchiveDownloader { _, _, cancelled, _ ->
                enteredDownload.set(true)
                while (!cancelled()) Thread.sleep(5)
                throw ToolchainCancelledException()
            },
        )
        ToolchainStore(stateDirectory, RUNNER_ID, ToolchainCatalog.load(), installer).use { store ->
            val plan = store.plan(ResolveToolchainPlanRequest(REQUIREMENT))
            val created = store.create(request(plan), PRINCIPAL, UUID.randomUUID().toString())
            await { enteredDownload.get() }

            store.cancel(created.installationId)
            val cancelled = awaitInstallation(store, created.installationId, ToolchainInstallationState.CANCELLED)

            assertEquals(ToolchainInstallationState.CANCELLED, cancelled.state)
            assertEquals(0L, sqlLong("SELECT COUNT(*) FROM toolchain_inventory"))
            assertEquals(1L, sqlLong("SELECT COUNT(*) FROM storage_reservations WHERE state='RELEASED'"))
            assertFalse(Files.exists(stateDirectory.resolve("toolchain-staging/${created.installationId}")))
        }
    }

    @Test
    fun `storage failure happens before download and current license consent persists`() {
        SQLiteJobStore(stateDirectory)
        sql("INSERT INTO storage_settings(area,budget_bytes,warning_percent,updated_at) VALUES('RUNNER_TOOLCHAIN',1,80,'2026-09-05T00:00:00Z')")
        val downloadStarted = AtomicBoolean(false)
        val installer = ToolchainInstaller(
            stateDirectory,
            archiveDownloader = ToolchainArchiveDownloader { _, _, _, _ -> downloadStarted.set(true) },
        )
        ToolchainStore(stateDirectory, RUNNER_ID, ToolchainCatalog.load(), installer).use { store ->
            val plan = store.plan(ResolveToolchainPlanRequest(REQUIREMENT))
            val created = store.create(request(plan), PRINCIPAL, UUID.randomUUID().toString())

            val failed = awaitInstallation(store, created.installationId, ToolchainInstallationState.FAILED)

            assertEquals("TOOLCHAIN_STORAGE_UNAVAILABLE", failed.reason?.code)
            assertFalse(downloadStarted.get())
            assertEquals(0L, sqlLong("SELECT COUNT(*) FROM toolchain_inventory"))
            assertTrue(store.plan(ResolveToolchainPlanRequest(REQUIREMENT)).requiredLicenses.isEmpty())
        }
    }

    @Test
    fun `removal preview is idempotent and execute only removes inventoried product path`() {
        SQLiteJobStore(stateDirectory)
        val archive = stateDirectory.resolve("fixture.zip")
        zip(archive)
        val artifact = fixtureArtifact(archive)
        val installer = ToolchainInstaller(
            stateDirectory,
            archiveDownloader = ToolchainArchiveDownloader { _, destination, _, progress ->
                Files.copy(archive, destination)
                progress(Files.size(destination))
            },
        )
        val installed = installer.install(UUID.randomUUID().toString(), artifact, { false }) { _, _ -> }
        sql(
            """INSERT INTO toolchain_inventory(artifact_id,component,version,archive_sha256,content_manifest_sha256,installed_bytes,relative_path,state,installed_at,checked_at)
                VALUES('${installed.artifactId}','GRADLE','fixture','${installed.archiveSha256}','${installed.contentManifestSha256}',${installed.installedBytes},'${installed.relativePath}','VERIFIED','2026-09-05T00:00:00Z','2026-09-05T00:00:00Z')""".trimIndent(),
        )
        ToolchainStore(stateDirectory, RUNNER_ID, ToolchainCatalog.load(), installer).use { store ->
            val key = UUID.randomUUID().toString()
            val first = store.removalPreview(ToolchainRemovalRequest(listOf(artifact.artifactId)), PRINCIPAL, key)
            val replay = store.removalPreview(ToolchainRemovalRequest(listOf(artifact.artifactId)), PRINCIPAL, key)
            assertEquals(first, replay)
            assertTrue(Files.exists(stateDirectory.resolve(installed.relativePath)))

            val operation = store.executeRemoval(ExecuteToolchainRemovalRequest(first.previewId), PRINCIPAL, UUID.randomUUID().toString())

            assertEquals(V2OperationState.COMPLETED, operation.state)
            assertFalse(Files.exists(stateDirectory.resolve(installed.relativePath)))
            assertEquals(0L, sqlLong("SELECT COUNT(*) FROM toolchain_inventory"))
        }
    }

    private fun request(plan: ToolchainPlanResponse) = CreateToolchainInstallationRequest(
        plan.planSha256,
        plan.catalogSha256,
        REQUIREMENT,
        plan.requiredLicenses.map { ToolchainLicenseAcceptanceRequest(it.licenseId, it.textSha256, true) },
    )

    private fun awaitInstallation(store: ToolchainStore, id: String, expected: ToolchainInstallationState): ToolchainInstallationResponse {
        var observed = store.installation(id)
        await {
            observed = store.installation(id)
            observed.state == expected
        }
        return observed
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Timed out waiting for toolchain state.")
            Thread.sleep(10)
        }
    }

    private fun fixtureArtifact(archive: Path) = ToolchainCatalogArtifact(
        artifactId = "gradle-fixture",
        component = ToolchainComponent.GRADLE,
        version = "fixture",
        platform = "linux-x86_64",
        url = "https://downloads.gradle.org/fixture.zip",
        archiveType = ToolchainArchiveType.ZIP,
        archiveSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)).toLowerHex(),
        archiveSizeBytes = Files.size(archive).toString(),
        maximumExpandedBytes = "1048576",
        maximumEntries = 16,
        installSubdirectory = "gradle/fixture",
        licenseId = "gradle-apache-2.0",
    )

    private fun zip(path: Path) {
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            mapOf("gradle-fixture/bin/gradle" to "x", "gradle-fixture/lib/marker" to "ok").forEach { (name, value) ->
                output.putNextEntry(ZipEntry(name)); output.write(value.encodeToByteArray()); output.closeEntry()
            }
        }
    }

    private fun sql(statement: String) = DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
        connection.createStatement().use { it.executeUpdate(statement) }
    }

    private fun sqlLong(statement: String): Long = DriverManager.getConnection("jdbc:sqlite:${stateDirectory.resolve("reprodroid-runner.sqlite3")}").use { connection ->
        connection.createStatement().use { it.executeQuery(statement).use { row -> row.next(); row.getLong(1) } }
    }

    companion object {
        private const val RUNNER_ID = "00000000-0000-4000-8000-000000000001"
        private const val PRINCIPAL = "local-development"
        private val REQUIREMENT = listOf(ToolchainRequirement(ToolchainComponent.GRADLE, "8.14.3"))
    }
}
