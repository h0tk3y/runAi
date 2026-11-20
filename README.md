# runAi

An experimental Gradle plugin for tasks that ask an AI model to do one narrow job. They behave like ordinary Gradle tasks: declared inputs and outputs, up-to-date checks, the configuration cache, and outputs that other tasks use as inputs.

The model is not deterministic. runAi makes the *build* repeatable instead:

- **Results are committed.** A task writes its result into the project, next to a lock file. The lock file holds a fingerprint of everything the model saw. You review and commit both, like any other source change.
- **CI never calls the model.** When the inputs match the lock file, the task only checks the committed result. When they do not match, a local build calls the model and updates the result, and a CI build fails and says what to run.
- **Answers are structured.** The model answers with JSON that matches a schema, and runAi writes the files. The model never writes files itself.
- **Fixed rules check every result.** Rules check every answer from the model, and every committed result, including hand edits. When an answer breaks a rule, runAi asks again with the problems listed. After `maxAttempts`, the task fails.
- **Changes stay small.** When a task runs again, its prompt includes the committed result, and asks the model to keep every part that the changes do not affect. A file the model keeps (`"keep": true`) is copied byte for byte. `--regenerate` starts over without the previous result.

## Plugins

| Plugin | For | What it adds |
|---|---|---|
| `dev.runai` | Build owners and plugin authors | The `runAi` extension with shared settings, the `RunAi` type, and `AbstractRunAi` for plugins. Registers no tasks. |
| `dev.runai.android-string-translations` | Android projects | Translation tasks for `strings.xml`, added to every variant. Built on `dev.runai`. |
| `dev.runai.spec-driven-kotlin` | Kotlin/JVM modules | Code and tests generated from a Markdown spec, and the spec published as a variant for dependent modules. Built on `dev.runai`. |

## Use it in a build: `RunAi`

The build owner registers `RunAi`s in a build script. Each one has instructions, input files, and the files that the result must contain:

```kotlin
import dev.runai.tasks.RunAi

plugins {
    id("dev.runai")
}

val summarizeDocs = tasks.register<RunAi>("summarizeDocs") {
    instructions = file("ai/summarize-docs.md") // what the model should do
    inputFiles.from("docs")                     // the model sees these, with their relative paths
    outputFiles = listOf("summary.md")          // the result must contain exactly these files
}

// Other tasks use the result like any task output. Gradle adds the task dependency.
tasks.register<Sync>("site") {
    from(summarizeDocs.flatMap { it.outputDirectory })
    into(layout.buildDirectory.dir("site"))
}
```

The model answers with the content of each declared file, and the task writes only those files. Built-in checks require exactly the declared files, none of them empty. The fingerprint covers the instructions, the input file paths and contents, the output file list, the model, the effort, and the prompt version.

To add your own rules, implement `AiResultCheck` and add it to `checks`. The task runs every check on each answer and on the committed result. It sends the problems back to the model on the next attempt:

```kotlin
class StartsWithHeading : dev.runai.tasks.AiResultCheck {
    override fun check(outputDir: File): List<String> =
        if (outputDir.resolve("summary.md").readText().startsWith("# ")) emptyList()
        else listOf("summary.md must start with a '# ' heading")
}

summarizeDocs {
    checks.add(StartsWithHeading())
}
```

A check that has settings declares them as properties with input annotations, such as `@get:Input`. Use a class, not a lambda: Gradle cannot track a lambda. Checks that several projects share belong in `buildSrc` or a convention plugin.

Other `RunAi` settings:
- `baseDirectory`: the base for the input paths the model sees. Defaults to the project directory.
- `maxInputBytes`: the most input to send. Defaults to 500,000 bytes, so a wide file pattern cannot send a whole repository.

Input files must be UTF-8 text.

## Build a plugin on it: `AbstractRunAi`

A plugin depends on the `dev.runai` plugin's artifact, applies `dev.runai`, and registers its own subclasses of `AbstractRunAi`. A subclass declares its inputs with the usual annotations and implements three functions:

- `fingerprint(fingerprint)`: adds everything the model sees to the fingerprint. The base class adds the model, the effort and `promptVersion`.
- `verify(outputDir)`: checks a committed result with fixed rules, and returns the problems.
- `generate(runner, outputDir)`: asks the model through `runner`, which checks the answers and retries, then writes the result.

The base class handles everything else: the lock file, the modes, staging (a failed run keeps the committed result), the transcript and the shared settings. For answers that are a set of text files, `FileAnswers` gives the schema, the parsing and the path checks. See the sample plugins in `sample-plugins/android-string-translations` and `sample-plugins/spec-driven-kotlin` for complete examples.

## Shared settings

```kotlin
runAi {
    model = "claude-haiku-5-5"     // the default; exact model ID; part of the fingerprint
    effort = "medium"              // low, medium, high, xhigh, max; part of the fingerprint
    maxTokens = 16_000L
    maxAttempts = 3                // attempts per request when answers break rules
    refusalFallback = true         // if the model declines, the API retries on a fallback model (not for Haiku, which has none)
    maxConcurrentRequests = 4      // across the whole build
    apiKey = "..."                 // default: ANTHROPIC_API_KEY, then the runAi.apiKey Gradle property
    baseUrl = "https://..."        // optional, for a proxy
}
```

Every runAi task takes these as defaults and can override them.

Never put the API key in a build script or in the project's `gradle.properties`, which are committed. Use the `ANTHROPIC_API_KEY` environment variable for terminal builds. Use `runAi.apiKey` in `~/.gradle/gradle.properties` for builds from Android Studio, which does not see variables exported in a terminal.

## Modes

In every mode except `off`, a task whose committed result matches its lock file only checks that result. The mode decides what happens when the result is missing, out of date, or breaks a rule.

| Mode | Missing or out-of-date result | Default |
|---|---|---|
| `live` | The task calls the model, writes the result and updates the lock file | Outside CI |
| `replay` | The task fails, and the message says what to run | On CI |
| `off` | The task is skipped; the committed result is used without checks | |

runAi detects CI from the `CI`, `JENKINS_URL`, `TEAMCITY_VERSION`, `TF_BUILD` or `CODEBUILD_BUILD_ID` environment variables (`CI=false` does not count). On other CI servers, pass `-PrunAi.mode=replay`. Set the mode with `-PrunAi.mode=live|replay|off`, or with `runAi.mode` in the build script. The mode is not a task input, so switching modes does not make tasks run again.

To call the model for a result that is up to date, for example after a compile error in generated code, pass `--regenerate` to the task in `live` mode: `./gradlew :parser:generateKotlinFromSpec --regenerate`.

Typical flow:

1. A developer changes an input. The next local build calls the model and updates the result in `runAi/`.
2. The developer reviews the diff in `runAi/` and commits it with the change.
3. CI builds in `replay` mode. It needs no API key and no build cache. If someone forgot to commit the updated result, CI fails.

## Outputs

Every runAi task has one result, `outputDirectory`, which contains only that result. A run that calls the model replaces its contents.

- By default, the result is in `runAi/<task name>/` in the project directory, and the lock file is `runAi/<task name>.lock`.
- `transcriptFile` (`build/runAi/transcripts/<task>.json`) holds every prompt, answer, problem list and token count of the last run that called the model. It is not an output, and it is not committed.
- At the end of a build that called the model, runAi prints `runAi: N model request(s), X input tokens, Y output tokens.`

To use a result in another task, wire `outputDirectory`, as in the `site` example above. This also works without knowing the task type: `tasks.withType<AbstractRunAi>().map { it.outputDirectory }`. Do not use the whole task (`from(task)` or `files(task)`) as an input: that includes the lock file.

## Android string translations

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("dev.runai.android-string-translations")
}

androidStringTranslations {
    locales("de", "fr", "pt-rBR")
    glossary = file("i18n/glossary.md")          // optional
    instructions = "Informal tone, short labels." // optional
    locales.named("de") { instructions = "Use 'du'." }
    // sourceStrings (default src/main/res/values/strings.xml), sourceLanguage (default "en"), chunkSize (default 100)
}
```

The plugin registers `translateStringsDe`, `translateStringsFr` and `translateStringsPtRbr`, plus `translateStrings` for all of them. It adds each result directory (`runAi/translations/<locale>/`) to every Android variant as a generated resource directory. There is no copy step, and one task serves all variants. Load runAi and the Android plugin in the same classloader, for example both in the root `build.gradle.kts` with `apply false`.

The task checks every translated value against its source, both in answers from the model and in committed files:

- the value is well-formed XML
- the same format specifiers (`%s`, `%1$d`, `%.2f`, `%%`)
- the same XML tags, and unchanged `<xliff:g>` contents
- every source name is present, and there are no extra names
- plurals use only `zero`, `one`, `two`, `few`, `many` and `other`, and include `other`

You can edit a committed translation by hand. The task keeps the edit if it follows these rules. Comments, formatting and non-translatable strings in `strings.xml` are not part of the fingerprint. Some fixes need no judgment, so the task applies them itself: it escapes apostrophes and double quotes, and escapes a leading `@` or `?`.

For a guided tour, see the demo app in [samples/android-app](samples/android-app/README.md).

## Spec-driven Kotlin

Each module keeps a Markdown spec in `src/main/spec`. Its `## API` section holds a `kotlin` code block with the package and the public declarations: the module's contract. The rest of the spec states what the module does, as numbered rules.

```kotlin
// <module>/build.gradle.kts
plugins {
    kotlin("jvm")
    id("dev.runai.spec-driven-kotlin")
}

dependencies {
    implementation(project(":lexer"))   // its spec becomes visible to this module's tasks
    testImplementation(kotlin("test"))  // the generated tests use kotlin.test
}

tasks.test { useJUnitPlatform() }

specDrivenKotlin {
    instructions = "Prefer immutable data."            // optional
    requiredFiles = listOf("com/example/cli/Main.kt")  // optional: files the code must contain
    // specDirectory defaults to src/main/spec
}
```

The plugin registers:

- `generateKotlinFromSpec`, which writes the implementation into `runAi/main/kotlin`, a source directory of `main`.
- `generateTestsFromSpec`, which writes tests into `runAi/test/kotlin`, a source directory of `test`. It does not see the implementation, so `test` checks the code against the spec.
- `generateFromSpec`, which runs both.

Each module publishes its spec in the `specElements` configuration, as a variant with `Category=documentation` and `DocsType=runai-spec`. Both tasks resolve the spec variants of the modules on `compileClasspath`, using variant reselection, so a module's prompts contain the specs of its dependencies.

Both tasks also get the whole compile classpath as an input: `compileClasspath` for the code, and `testCompileClasspath`, which includes the module's own classes, for the tests. That covers third-party libraries as much as project modules. The model explores the classpath with four read-only tools: `list_packages`, `list_classes`, `describe_class` and `search_classpath`. They read class files with ASM and show the public API only, never method bodies. The fingerprint covers the same public API of every classpath entry, so:
- a library version that changes its API makes the result out of date;
- a change to a dependency's spec makes the dependent results out of date;
- a change inside a dependency's method bodies does not.

Fixed rules check the generated sources before they are written:
- `.kt` files at the path of their package, in a package from the API section.
- Imports only from packages on the compile classpath, the Java standard library, the module itself, and the packages the dependency specs declare.
- Every name in the API section declared.
- The `requiredFiles` present.
- A `@Test` in every test file.

The model must not guess. It reports an issue instead of generating code in two cases:
- **Contradiction:** the module's spec clearly contradicts the spec of a dependency.
- **Unclear:** a spec leaves open a decision that changes what the API does. The model quotes the statement closest to the gap, and asks a question you can answer in the spec.

Quotes must be word for word. A fixed check confirms that each quote appears in the spec it names, and an invented quote goes back to the model as a problem. A confirmed report fails the task without a retry, and nothing is generated. The message points to each quote as `file:///…:line:column`, the form Kotlin compiler errors use, so IDEs and terminals show it as a link:

```text
The specification of :cli contradicts the specification of its dependency :lexer, so runAi did not generate anything.
Resolve the points below in the specifications, then run ':cli:generateKotlinFromSpec' again.

1. Contradiction
   :lexer at file:///…/calculator-cli/lexer/src/main/spec/lexer.md:21:4
     "Whitespace are not part of any token and should not appear in expressions, even as separators."
   :cli at file:///…/calculator-cli/cli/src/main/spec/cli.md:19:4
     "On success, `run` writes the formatted value and a line break to `out`, and returns `0`. For example, `2 * (3 + 4)` writes `14`, …"
   Why they conflict: The lexer does not allow the spaces in `2 * (3 + 4)` …, so `run` cannot write `14` and return `0`.
```

Plugin authors can use the same mechanism: a validator that returns `Validation.NeedsDecision(message)` stops the task with that message, without a retry.

The demo in [samples/calculator-cli](samples/calculator-cli/README.md) builds a command-line calculator from six modules this way.

## Development

```bash
./gradlew check
```

- `core`: unit tests for fingerprints, lock files and the retry loop. TestKit tests for `RunAi` run against a local fake of the Messages API, so they make no real model calls.
- `sample-plugins/android-string-translations`: unit tests for parsing, checks and prompts. TestKit tests cover the modes, out-of-date detection, hand edits, failed generation, configuration-cache reuse, downstream tasks and the request format. `AndroidFunctionalTest` builds a small app with AGP, so aapt2 compiles the translations. It is skipped when no Android SDK is found.
- `sample-plugins/spec-driven-kotlin`: unit tests for spec parsing and the source checks. TestKit tests build two Kotlin modules with the real Kotlin Gradle plugin: generation, the spec variant across modules, replay from a fresh clone, out-of-date detection, and retries.
- `build-logic`: the convention plugin the plugin modules share.
- `samples/android-app` and `samples/calculator-cli`: demos that use the plugins from this repository. They call the real Claude API; see their READMEs.

Requirements: Gradle 9.9.2 (wrapper), JDK 17. Versions are in `gradle/libs.versions.toml`.

## Status and known limits

This is a proof of concept.

- When the inputs of a task change, the model is asked to keep what the changes do not affect, and kept files are copied byte for byte. But within a file it regenerates, the model may still reword parts that did not need to change. Only the prompt asks it not to.
- Tasks put their declared inputs in the prompt, and spec tasks add read-only tools over their declared classpath, so the model sees only declared inputs. There are no tools to run code or to write files.
- `RunAi` reads and writes text files only.
- runAi does not see compile errors or test failures of generated code, so it cannot send them back to the model. Fix the spec, use `--regenerate`, or edit the committed code. A `compile` tool next to the classpath tools would close this loop.
- The Anthropic SDK is a normal plugin dependency. Its OkHttp and Jackson versions can clash with other plugins in the same classloader. Worker API isolation or shading would fix this.
- Plurals are checked against the allowed quantities, not against the CLDR rules of the target locale. `<string-array>` resources are not translated.
- In a multi-project build, the first project that applies `dev.runai` configures the shared service (API key, URL, concurrency).
- `dev.runai` is a placeholder plugin ID.
