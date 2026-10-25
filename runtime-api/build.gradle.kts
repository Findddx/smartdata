import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":foundation-kernel"))
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.jackson.bom))
    // The official OpenAI SDK keeps the Jackson 2 namespace; patch it independently.
    implementation(platform(libs.jackson2.bom))
    implementation(platform(libs.spring.ai.bom))
    implementation(kotlin("reflect"))
    implementation(libs.jackson.kotlin)
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.validation)
    implementation(libs.spring.boot.security)
    implementation(libs.spring.boot.oauth2.resource.server)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.jdbc)
    implementation("org.jooq:jooq")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.security.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    constraints {
        // Patch the complete embedded Tomcat family together. Apache's current
        // security advisory also covers fixes newer than the scanner database.
        listOf("core", "el", "websocket").forEach { component ->
            implementation("org.apache.tomcat.embed:tomcat-embed-$component:${libs.versions.tomcat.get()}") {
                because("Apache Tomcat 11 security fixes through 11.0.27; see docs/architecture/0005-security-patch-baseline.md")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Real PostgreSQL checks are a separate mandatory CI gate. Unit tests must not
// silently substitute an embedded database when Docker is unavailable.
val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, platform(libs.testcontainers.bom))
    add(integrationTest.implementationConfigurationName, libs.testcontainers.postgresql)
}

tasks.register<Test>("integrationTest") {
    description = "Runs mandatory PostgreSQL 18 integration tests against a real Testcontainers database."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    maxHeapSize = "768m"
    shouldRunAfter(tasks.test)
    failOnNoDiscoveredTests.set(true)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    systemProperty("org.jooq.no-logo", "true")
    systemProperty("org.jooq.no-tips", "true")
    systemProperty("smartdata.integration.checkoutRoot", rootProject.projectDir.absolutePath)
    // This remains false/default in Testcontainers as well; absence is a test
    // infrastructure failure, never a reason to report a skipped success.
    environment("TESTCONTAINERS_RYUK_DISABLED", "false")
    providers.gradleProperty("postgresPortableMetadata").orNull?.let {
        systemProperty("smartdata.integration.portableMetadata", it)
    }
}
