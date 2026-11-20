plugins {
    id("graidle.gradle-plugin")
    `java-test-fixtures`
}

dependencies {
    implementation(libs.anthropic.java)
    implementation(libs.jackson.databind)
}

gradlePlugin {
    plugins {
        create("graidle") {
            id = "dev.graidle"
            implementationClass = "dev.graidle.GraidlePlugin"
            displayName = "grAIdle"
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
