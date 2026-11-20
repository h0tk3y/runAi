plugins {
    id("calc.module")
    application
}

dependencies {
    implementation(project(":lexer"))
    implementation(project(":parser"))
    implementation(project(":evaluator"))
    implementation(project(":format"))
    // A real third-party library. The model looks up its API on the compile classpath with runAi's tools.
    implementation("com.github.ajalt.clikt:clikt:5.2.1")
}

specDrivenKotlin {
    // The application plugin needs to know the class with `main`, so the file name is fixed.
    requiredFiles = listOf("dev/runai/demo/calc/cli/Main.kt")
}

application {
    applicationName = "calc"
    mainClass = "dev.runai.demo.calc.cli.MainKt"
}

tasks.named("run", JavaExec::class.java)
