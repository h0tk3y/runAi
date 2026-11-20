# grAIdle demo: calc, a calculator built from specs

`calc` evaluates arithmetic expressions on the command line:

```text
$ calc 2 ^ 3 ^ 2
512
$ calc "2 * (3"
Error: Missing ')'
  2 * (3
        ^
```

It has six small modules. Each module has only a Markdown spec, in `src/main/spec`. grAIdle's `dev.graidle.spec-driven-kotlin` plugin generates the Kotlin code and the tests of each module from its spec. You review and commit the results, like any other source change.

```text
ast      lexer      format
 ^  ^      ^          ^
 |   \     |          |
 |    parser          |
 |      ^             |
evaluator             |
   ^    |             |
   |    |             |
   +---cli------------+
```

`cli` depends on `lexer`, `parser`, `evaluator` and `format`; `parser` depends on `ast` and `lexer`; `evaluator` depends on `ast`.

## How it works

Each module applies the `calc.module` convention plugin from `build-logic`. It applies the Kotlin plugin and `dev.graidle.spec-driven-kotlin`, and adds `kotlin.test`. The grAIdle plugins come from this repository: `build-logic` includes it as a composite build.

A spec has two parts:

- **`## API`**: a `kotlin` code block with the package and the public declarations of the module. It is the contract with the other modules.
- **Rules**: numbered statements of what the module does, including the errors.

For each module, the plugin registers two grAIdle tasks:

| Task | Writes | Into |
|---|---|---|
| `generateKotlinFromSpec` | the implementation | `graidle/main/kotlin`, a source directory of `main` |
| `generateTestsFromSpec` | tests, one or more per rule | `graidle/test/kotlin`, a source directory of `test` |

The two tasks never see each other's output. They work from the same spec separately, so `test` checks the code against the spec.

Each module also publishes its spec as a `graidle-spec` variant. Both tasks read the specs of the modules on the compile classpath from that variant. So `parser` sees the specs of `ast` and `lexer`. Check what a module publishes with `./gradlew :ast:outgoingVariants --variant specElements`.

The model also explores the module's compile classpath with read-only tools that show the public API of every class on it, but never method bodies. That is how `cli` uses Clikt, a real third-party library from Maven Central: the model looks up Clikt's commands, arguments and errors in the jars, as it does for the classes of `lexer` or `parser`.

Before the code is written, fixed rules check every answer from the model:

- every file is a `.kt` file at the path of its package
- the packages are the ones the API section declares
- imports come only from packages on the compile classpath and the Java standard library. The code cannot use a library that the build does not have.
- every name in the API section is declared
- `cli` includes `Main.kt`, because the `application` plugin needs the class name
- every test file has a `@Test`

When an answer breaks a rule, grAIdle asks again with the problems listed.

The model does not guess. When a module's spec clearly contradicts the spec of a dependency, or leaves open a decision that changes what the API does, it reports that, quoting the specs word for word. grAIdle checks that the quotes are real. Then it stops the task, and asks you to resolve the points in the specs. Each point links to the line in the spec file.

## 1. Prerequisites

- **JDK 17.** Gradle downloads one if it finds none.
- **A Claude API key.** Use either:
  - the `ANTHROPIC_API_KEY` environment variable, for terminal builds
  - `graidle.apiKey=...` in `~/.gradle/gradle.properties`, for builds from an IDE

All commands run in this directory (`samples/calculator-cli`).

## 2. Generate the code and the tests

```bash
./gradlew generateFromSpec
```

This makes 12 model requests with `claude-haiku-5-5`, the default: code and tests for six modules. Tool calls add turns to a request. Generation runs from the bottom up, because each module compiles against the modules it depends on. The results are in `<module>/graidle/main/kotlin` and `<module>/graidle/test/kotlin`, with a `.lock` file next to each. The transcripts are in `<module>/build/graidle/transcripts/`.

## 3. Build, test and run

```bash
./gradlew build
```

This compiles the generated code, and runs the generated tests against it. Then run the calculator:

```bash
./gradlew :cli:installDist
```

```bash
cli/build/install/calc/bin/calc 2 ^ 3 ^ 2
```

## 4. Review and commit

Read the generated code and tests like any other change, then commit the `graidle/` directory of each module, including the `.lock` files.

In this repository, `.gitignore` excludes the samples' `graidle/` folders, so your demo runs are not committed here. In your own project, commit them.

## 5. Build like CI

On CI, grAIdle uses the committed results, needs no API key, and never calls the model:

```bash
env -u ANTHROPIC_API_KEY CI=true ./gradlew build
```

## When the generated code is wrong

grAIdle does not yet see compiler errors or test failures, so it cannot send them back to the model. When `compileKotlin` or `test` fails:

1. **Read the failure.** A failing test names the rule it checks. Decide whether the code or the test is wrong, or whether the spec is unclear.
2. **Fix the cause:**
   - If the spec is unclear, make the rule more precise. The changed spec makes both results of that module out of date, so the next local build generates them again.
   - If the spec is fine and only one side is wrong, generate that side again:

     ```bash
     ./gradlew :parser:generateKotlinFromSpec --regenerate
     ```

   - Or fix the committed code by hand. grAIdle keeps a hand edit as long as it follows the rules above.

## Things to try

- **Change a rule.** In `lexer/src/main/spec/lexer.md`, allow `.5` as a number. The lexer's code and tests are now out of date. So are those of `parser` and `cli`, because they see the lexer spec. Run `./gradlew build` locally to generate them again, or `CI=true ./gradlew build` to see CI reject the change.
- **Change code, not a spec.** Edit the inside of a function in the committed code of `lexer`. The other modules stay up to date: they depend on the lexer spec and on its public API, not on its method bodies.
- **Change a rule, keep the rest.** Change one rule in a spec and build. The prompt includes the committed result and asks the model to keep what the change does not affect, so the diff stays small.
- **Create a contradiction.** Add this rule to `cli/src/main/spec/cli.md`: "`parse` returns `null` for an empty list of tokens, and `run` then writes nothing and returns `0`." Rule 4 of the parser spec says that `parse` throws `ParseException("Empty expression", 0)` instead. Run `./gradlew :cli:generateKotlinFromSpec`. The task should stop, quote both rules, and ask you to resolve the contradiction, instead of picking one.
- **Add a module.** For example, add `memory`, which stores the last result as `ans`. Write its spec, add it to `settings.gradle.kts`, and add the dependencies. Only the new module and the modules that now depend on it are generated.

## Design notes

- Dependent modules see the full spec of each dependency, not only the API section. The rules hold details they need: for example, `cli` needs to know which position a `ParseException` reports. As a result, any change to a spec makes its dependents out of date, even a change of wording.
- `parser` uses `api(project(":ast"))` and `api(project(":lexer"))`, because its API takes tokens and returns tree nodes. So modules that depend on `parser` see the `ast` and `lexer` specs too.
