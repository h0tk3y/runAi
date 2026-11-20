plugins {
    // Load both plugins in one classloader, so grAIdle can see the Android plugin's classes.
    id("com.android.application") version "9.5.1" apply false
    id("dev.graidle.android-string-translations") apply false
}
