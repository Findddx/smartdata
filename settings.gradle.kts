pluginManagement {
    repositories {
        System.getenv("SMARTDATA_GRADLE_PLUGIN_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        System.getenv("SMARTDATA_MAVEN_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        mavenCentral()
    }
}

rootProject.name = "smartdata-foundation"

include(
    "foundation-kernel",
    "domain-compiler",
    "runtime-api",
    "workflow-worker",
)
