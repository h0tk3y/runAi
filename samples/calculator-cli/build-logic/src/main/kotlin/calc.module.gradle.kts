// Every module of the calculator: Kotlin/JVM, code and tests generated from src/main/spec.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("dev.runai.spec-driven-kotlin")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The generated tests use kotlin.test.
    testImplementation(kotlin("test"))
}

runAi {
    model = "claude-haiku-5-5"
}

tasks.test {
    useJUnitPlatform()
}

specDrivenKotlin {
    instructions = "Keep the code small and readable. Prefer immutable data and pure functions."
}
