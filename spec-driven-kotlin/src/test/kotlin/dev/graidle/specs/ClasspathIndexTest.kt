package dev.graidle.specs

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** Reads real jars from the test classpath: AssertJ (Java) and the Kotlin standard library. */
class ClasspathIndexTest {

    private fun jar(name: String) = System.getProperty("java.class.path").split(File.pathSeparator)
        .map(::File).first { it.name.startsWith(name) && it.name.endsWith(".jar") }

    private val assertj = jar("assertj-core")
    private val stdlib = jar("kotlin-stdlib")
    private val index = ClasspathIndex.of(listOf(assertj, stdlib))

    @Test
    fun `reads public classes and their members`() {
        val assertions = index.classes.getValue("org.assertj.core.api.Assertions")

        assertThat(assertions.declaration).startsWith("public class org.assertj.core.api.Assertions")
        assertThat(assertions.members).anyMatch { it.startsWith("public static assertThat(java.lang.String): ") }
        assertThat(index.packages).containsKey("org.assertj.core.api")
        assertThat(index.packageEntries.getValue("org.assertj.core.api")).containsExactly(assertj.name)
    }

    @Test
    fun `marks Kotlin classes and keeps generic types`() {
        val regex = index.classes.getValue("kotlin.text.Regex")

        assertThat(regex.isKotlin).isTrue()
        assertThat(regex.members).contains("public final matches(java.lang.CharSequence): boolean")
        assertThat(index.membersOf(index.classes.getValue("kotlin.collections.CollectionsKt")))
            .anyMatch { it.startsWith("public static final listOf<T>(T): java.util.List<T>") }
    }

    @Test
    fun `leaves out anonymous classes and non-public members`() {
        assertThat(index.classes.keys).noneMatch { Regex("""\$\d""").containsMatchIn(it) }
        assertThat(index.classes.values.flatMap { it.members }).noneMatch { it.startsWith("private ") }
    }

    @Test
    fun `the API hash of an entry is stable`() {
        assertThat(ClasspathIndex.of(listOf(assertj)).entries.single().apiHash)
            .isEqualTo(index.entries.first().apiHash)
            .hasSize(64)
    }

    @Test
    fun `the tools show packages, classes, members and search results`() {
        val tools = ClasspathTools.tools(index).associateBy { it.name }

        assertThat(tools.getValue("list_packages").handler("{}")).contains("org.assertj.core.api (")
        assertThat(tools.getValue("list_classes").handler("""{"package":"kotlin.text"}""")).contains("class kotlin.text.Regex  // Kotlin")
        assertThat(tools.getValue("describe_class").handler("""{"name":"kotlin.text.Regex"}"""))
            .startsWith("public final class kotlin.text.Regex")
            .contains("  public final matches(java.lang.CharSequence): boolean")
        assertThat(tools.getValue("search_classpath").handler("""{"query":"assertThatThrownBy"}"""))
            .contains("org.assertj.core.api.Assertions: public static assertThatThrownBy(")
        assertThat(tools.getValue("describe_class").handler("""{"name":"com.example.Missing"}"""))
            .isEqualTo("There is no public class 'com.example.Missing' on the classpath. Use search_classpath.")
    }
}
