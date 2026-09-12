import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

abstract class VerifyReleaseBundleTask : DefaultTask() {
    @get:InputFile
    abstract val distributionArchive: RegularFileProperty

    @get:InputFile
    abstract val noticesFile: RegularFileProperty

    @get:InputFile
    abstract val bomFile: RegularFileProperty

    @TaskAction
    fun verify() {
        ZipFile(distributionArchive.get().asFile).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            check(names.any { it.endsWith("/LICENSE") }) { "Runner distribution omits LICENSE" }
            check(names.any { it.endsWith("/THIRD_PARTY_LICENSES.md") }) {
                "Runner distribution omits THIRD_PARTY_LICENSES.md"
            }
        }
        val notices = noticesFile.get().asFile.readText()
        check(notices.contains("SQLite JDBC 3.51.1.0 embedded native notices"))
        check(notices.contains("Copyright (c) 2006, David Crawshaw"))
        check(notices.contains("Java JSON Canonicalization (RFC 8785)"))
        val bom = bomFile.get().asFile.readText()
        check(bom.contains("sqlite-jdbc")) { "Runner SBOM omits sqlite-jdbc" }
        check(bom.contains("java-json-canonicalization")) {
            "Runner SBOM omits Java JSON Canonicalization"
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.cyclonedx.bom") version "3.4.1"
    application
}

group = "com.sanka1610.reprodroid.runner"
version = "0.1.0-alpha02"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.sanka1610.reprodroid.runner.ApplicationKt"
}

distributions {
    main {
        contents {
            from("LICENSE", "THIRD_PARTY_LICENSES.md")
        }
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jcs)
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pkix)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("runtimeClasspath")
    projectType = Component.Type.APPLICATION
    includeLicenseText = true
}

tasks.register<VerifyReleaseBundleTask>("verifyReleaseBundle") {
    val distribution = tasks.named<Zip>("distZip")
    dependsOn(distribution, tasks.named("cyclonedxBom"))
    distributionArchive.set(distribution.flatMap { it.archiveFile })
    noticesFile.set(layout.projectDirectory.file("THIRD_PARTY_LICENSES.md"))
    bomFile.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
}
