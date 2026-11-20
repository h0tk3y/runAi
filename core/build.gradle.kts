plugins {
    id("runai.gradle-plugin")
    `java-test-fixtures`
}

dependencies {
    implementation(libs.anthropic.java)
    implementation(libs.jackson.databind)
}

gradlePlugin {
    plugins {
        create("runAi") {
            id = "dev.runai"
            implementationClass = "dev.runai.RunAiPlugin"
            displayName = "runAi"
            description = "Gradle tasks that ask an AI model for a result, committed with a lock file."
        }
    }
}

testing {
    suites {
        named<JvmTestSuite>("functionalTest") {
            dependencies {
                implementation(testFixtures(project()))
            }
        }
    }
}
