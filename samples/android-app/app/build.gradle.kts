plugins {
    id("com.android.application")
    id("dev.graidle.android-string-translations")
}

android {
    namespace = "dev.graidle.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.graidle.demo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

// Settings shared by all grAIdle tasks, from the core plugin. The defaults are fine for this demo:
graidle { model = "claude-haiku-5-5" }

androidStringTranslations {
    locales("de", "fr", "ja", "pt-rBR")
    glossary = file("i18n/glossary.md")
    instructions = "Notely is a note-taking app. Keep button labels short."
    locales.named("de") { instructions = "Address the user with 'du', not 'Sie'." }
}
