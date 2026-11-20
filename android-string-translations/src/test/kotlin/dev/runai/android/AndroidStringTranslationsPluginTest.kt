package dev.runai.android

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AndroidStringTranslationsPluginTest {

    @Test
    fun `task names come from Android qualifiers`() {
        assertThat(AndroidStringTranslationsPlugin.taskSuffix("de")).isEqualTo("De")
        assertThat(AndroidStringTranslationsPlugin.taskSuffix("pt-rBR")).isEqualTo("PtRbr")
        assertThat(AndroidStringTranslationsPlugin.taskSuffix("b+sr+Latn")).isEqualTo("BSrLatn")
    }
}
