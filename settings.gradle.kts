pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "runAi"

// The general-purpose plugin: AI tasks, modes, lock files. No Android or other domain code.
include(":core")
// Translates Android string resources. Built on :core, as other plugins can be.
include(":android-string-translations")
// Generates Kotlin code and tests from Markdown specs, and publishes specs as a variant. Built on :core.
include(":spec-driven-kotlin")
