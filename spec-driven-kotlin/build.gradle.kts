plugins {
    id("runai.gradle-plugin")
}

dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
    // Reads the public API of the compile classpath from class files.
    implementation(libs.asm)
    implementation(libs.asm.util)
}

gradlePlugin {
    plugins {
        create("specDrivenKotlin") {
            id = "dev.runai.spec-driven-kotlin"
            implementationClass = "dev.runai.specs.SpecDrivenKotlinPlugin"
            displayName = "runAi spec-driven Kotlin"
            description = "Generates Kotlin code and tests from Markdown specs, and publishes the specs to dependent modules."
        }
    }
}

testing {
    suites {
        named<JvmTestSuite>("functionalTest") {
            dependencies {
                implementation(testFixtures(project(":core")))
            }
            targets.configureEach {
                testTask.configure {
                    // The test builds apply the Kotlin plugin from the Gradle Plugin Portal.
                    systemProperty("runAi.test.kotlinVersion", libs.versions.kotlin.get())
                }
            }
        }
    }
}
