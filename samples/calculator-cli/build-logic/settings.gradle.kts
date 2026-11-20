dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// The runAi repository: its `dev.runai:*` modules replace the published plugins.
includeBuild("../../..")

rootProject.name = "build-logic"
