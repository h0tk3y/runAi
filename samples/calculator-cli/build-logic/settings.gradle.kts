dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// The grAIdle repository: its `dev.graidle:*` modules replace the published plugins.
includeBuild("../../..")

rootProject.name = "build-logic"
