# grAIdle demo: Notely

A one-screen Android app that uses grAIdle to translate its strings into German, French, Japanese and Brazilian Portuguese. The demo uses the plugin source from this repository, so it always runs the current code.

The strings in `app/src/main/res/values/strings.xml` cover the cases the translation task must handle:

- format specifiers (`%1$s`, `%1$d`, `%1$.1f%%`)
- a plural (Japanese has only one plural form, so its answer must differ from English)
- `<xliff:g>` content that must stay unchanged
- `<b>` markup, and apostrophes that must be escaped
- a string marked `translatable="false"`

`app/i18n/glossary.md` fixes the product terms. `app/build.gradle.kts` applies `dev.graidle.android-string-translations` and adds instructions for all locales and for German only, in the `androidStringTranslations` block.

All commands run in this directory (`samples/android-app`).

## 1. Prerequisites

- **JDK 17.** `gradle/gradle-daemon-jvm.properties` asks for it, and Gradle downloads one if it finds none.
- **Android SDK with platform 36.** Set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=/path/to/sdk`. Do not commit `local.properties`.

Check the setup without the model. This builds the app without translations:

```bash
./gradlew :app:assembleDebug -Pgraidle.mode=off
```

## 2. Connect Claude

Create an API key in the Claude Console. Then give it to Gradle in one of two ways:

- **Environment variable**, for terminal builds. Gradle passes your shell environment to the daemon on every build.

  ```bash
  export ANTHROPIC_API_KEY=sk-ant-...
  ```

- **User Gradle properties**, for builds started from Android Studio, which does not see variables you export in a terminal. Add this line to `~/.gradle/gradle.properties`. That file is in your home directory, not in the project, so the key is never committed:

  ```properties
  graidle.apiKey=sk-ant-...
  ```

To use a different model, set `graidle { model = "..." }` in `app/build.gradle.kts`. The default is `claude-opus-5-5`; `claude-haiku-5-5` costs much less. The model ID is part of the fingerprint, so changing it makes every committed result out of date.

## 3. Generate the translations

Outside CI, grAIdle runs in `live` mode, so a normal build generates missing translations:

```bash
./gradlew :app:translateStrings
```

Expect four model requests, one for each locale. The build ends with a line such as `grAIdle: 4 model request(s), ... input tokens, ... output tokens.`

The results are in `app/graidle/translations/`:

```text
app/graidle/translations/
  de/values-de/strings.xml
  de.lock
  fr/values-fr/strings.xml
  fr.lock
  ja/values-ja/strings.xml
  ja.lock
  pt-rBR/values-pt-rBR/strings.xml
  pt-rBR.lock
```

To see what the model was asked and what it answered, open `app/build/graidle/transcripts/translateStringsJa.json`. It also lists retries, and the problems that caused them.

## 4. Review, run and commit

1. Read the generated `strings.xml` files like any other code change.
2. Install the app and switch the device language to see a translation:

   ```bash
   ./gradlew :app:installDebug
   ```

3. Commit `app/graidle/`, including the `.lock` files.

## 5. Build like CI

On CI, grAIdle runs in `replay` mode: it uses the committed results, needs no API key, and never calls the model. It detects CI from `CI`, `JENKINS_URL`, `TEAMCITY_VERSION`, `TF_BUILD` or `CODEBUILD_BUILD_ID`. On other CI servers, add `-Pgraidle.mode=replay`.

Simulate a CI build without your key:

```bash
env -u ANTHROPIC_API_KEY CI=true ./gradlew :app:assembleDebug
```

## 6. Things to try

- **Forget to update translations.** Change `Share` to `Share note` in `strings.xml`, then build like CI. The build fails, says the inputs changed, and gives the command to run. A local `./gradlew :app:assembleDebug` updates the translations instead; review the diff in `app/graidle/`.
- **Change something that does not matter.** Add a comment or reformat `strings.xml`. The fingerprint does not change, so nothing is generated again.
- **Fix a translation by hand.** Change a word in `app/graidle/translations/de/values-de/strings.xml` and build like CI. The edit is kept.
- **Break a translation by hand.** Remove `%1$s` from the German `greeting` and build like CI. The build fails and names the broken rule.
- **Add a locale.** Add `"es"` to `locales(...)` and build. Only the new locale is generated.

## Troubleshooting

- **"No API key is configured"**: see step 2. If you build from Android Studio, use `~/.gradle/gradle.properties`.
- **"grAIdle cannot use the committed result"** in a local build: you are in `replay` mode. Check for `CI` in your environment, or `graidle.mode` in a `gradle.properties` file.
- **`NoClassDefFoundError` for an Android class**: grAIdle and the Android plugin must load in the same classloader. Keep both in the root `build.gradle.kts` with `apply false`, as this demo does.
