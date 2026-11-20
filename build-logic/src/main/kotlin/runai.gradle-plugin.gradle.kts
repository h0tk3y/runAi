// Shared setup for the runAi plugin modules: Kotlin DSL plugin development, JDK 17, unit tests and
// TestKit functional tests.

plugins {
    id("org.gradle.kotlin.kotlin-dsl")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val junitVersion = libs.findVersion("junit").get().requiredVersion
val assertj = libs.findLibrary("assertj").get()

group = "dev.runai"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter(junitVersion)
            dependencies {
                implementation(assertj)
            }
        }

        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(junitVersion)
            dependencies {
                implementation(gradleTestKit())
                implementation(assertj)
            }
            targets.configureEach {
                testTask.configure { shouldRunAfter(tasks.named("test")) }
            }
        }
    }
}

gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
