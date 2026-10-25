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
    // Temporal also uses Jackson 2; the SDK-only patch must cover the worker.
    implementation(platform(libs.jackson2.bom))
    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(libs.temporal.sdk)
    implementation(libs.temporal.kotlin)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.temporal.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
