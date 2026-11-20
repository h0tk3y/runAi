pluginManagement {
    // The convention plugin that every module applies. It brings in the runAi plugins from this repository.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "calculator-cli"

// Each module is built from its own spec in src/main/spec. Dependencies, from the bottom up:
//   ast, lexer, format  -> no dependencies
//   parser              -> ast, lexer
//   evaluator           -> ast
//   cli                 -> lexer, parser, evaluator, format
include(":ast", ":lexer", ":parser", ":evaluator", ":format", ":cli")
