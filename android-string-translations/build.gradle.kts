plugins {
    id("graidle.gradle-plugin")
}

dependencies {
    api(project(":core"))
    implementation(libs.jackson.databind)
    // AGP classes are only used when an Android plugin is applied.
    compileOnly(libs.android.gradle.api)
}

gradlePlugin {
    plugins {
        create("androidStringTranslations") {
            id = "dev.graidle.android-string-translations"
            implementationClass = "dev.graidle.android.AndroidStringTranslationsPlugin"
            displayName = "grAIdle Android string translations"
            description = "Translates Android string resources with grAIdle, and adds them to every variant."
        }
    }
}

testing {
    suites {
        named<JvmTestSuite>("functionalTest") {
            dependencies {
                implementation(testFixtures(project(":core")))
            }
        }
    }
}

// Functional tests apply AGP from the injected plugin classpath, so the plugin under test can see AGP classes.
val agpForTests = configurations.dependencyScope("agpForFunctionalTests")
val agpForTestsClasspath = configurations.resolvable("agpForFunctionalTestsClasspath") {
    extendsFrom(agpForTests.get())
    val runtime = configurations.runtimeClasspath.get().attributes
    attributes {
        runtime.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            attribute(key as Attribute<Any>, runtime.getAttribute(key)!!)
        }
    }
}
dependencies {
    agpForTests(libs.android.gradle)
}
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(agpForTestsClasspath)
}
