plugins {
    id("com.android.application")
    id("dev.runai.android-string-translations")
}

android {
    namespace = "dev.runai.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.runai.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

// Settings shared by all runAi tasks, from the core plugin. The defaults are fine for this demo:
runAi { model = "claude-haiku-5-5" }

androidStringTranslations {
    locales("de", "fr", "ja", "pt-rBR")
    glossary = file("i18n/glossary.md")
    instructions = "Notely is a note-taking app. Keep button labels short."
    locales.named("de") { instructions = "Address the user with 'du', not 'Sie'." }
}
