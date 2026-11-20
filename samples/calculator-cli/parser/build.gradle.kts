plugins {
    id("calc.module")
}

dependencies {
    // The parser's API takes lexer tokens and returns AST nodes, so dependents need both.
    api(project(":ast"))
    api(project(":lexer"))
}
