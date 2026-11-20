plugins {
    id("calc.module")
    application
}

dependencies {
    implementation(project(":lexer"))
    implementation(project(":parser"))
    implementation(project(":evaluator"))
    implementation(project(":format"))
}

specDrivenKotlin {
    // The application plugin needs to know the class with `main`, so the file name is fixed.
    requiredFiles = listOf("dev/graidle/demo/calc/cli/Main.kt")
}

application {
    applicationName = "calc"
    mainClass = "dev.graidle.demo.calc.cli.MainKt"
}

tasks.named("run", JavaExec::class.java)
