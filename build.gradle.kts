import org.cyclonedx.Version
import org.cyclonedx.gradle.CyclonedxAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.cyclonedx)
}

group = "io.smartdata"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
}

allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        // Keep SBOM generation bounded and network-independent after Gradle has
        // resolved the locked dependency graph. License policy
        // is enforced separately by dependency-review; resolving every upstream
        // POM here would add mutable network I/O to the release gate.
        includeMetadataResolution.set(false)
        includeBuildEnvironment.set(false)
        includeLicenseText.set(false)
        schemaVersion.set(Version.VERSION_16)
    }
}

tasks.named<CyclonedxAggregateTask>("cyclonedxBom") {
    schemaVersion.set(Version.VERSION_16)
    includeBomSerialNumber.set(true)
    includeLicenseText.set(false)
    jsonOutput.set(layout.buildDirectory.file("reports/sbom/smartdata.cdx.json"))
    xmlOutput.set(layout.buildDirectory.file("reports/sbom/smartdata.cdx.xml"))
}

tasks.wrapper {
    gradleVersion = "9.3.0"
    distributionType = Wrapper.DistributionType.BIN
}
