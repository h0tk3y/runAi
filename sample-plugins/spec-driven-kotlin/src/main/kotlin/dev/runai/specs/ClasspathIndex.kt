package dev.runai.specs

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.util.TraceSignatureVisitor
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** The public API of one class: a declaration line and one line per public or protected member. */
data class ClassApi(
    /** The binary name with dots, for example `demo.lexer.Token` or `demo.lexer.Token$Companion`. */
    val name: String,
    val packageName: String,
    val kind: String,
    val declaration: String,
    val members: List<String>,
    val isKotlin: Boolean,
    /** False for classes that only matter through a public subclass, such as the parts of a Kotlin multifile facade. */
    val isPublic: Boolean,
    val superName: String?,
)

/**
 * The public API of every class on a classpath, read from the class files without loading them. It backs the
 * classpath tools of the spec tasks, and its hash per entry is what the fingerprint uses: a change to a method body
 * or a private member does not change it.
 */
class ClasspathIndex private constructor(val entries: List<Entry>) {

    /** One classpath entry: a jar or a classes directory. [label] is a short name for people and the model. */
    class Entry(val label: String, val classes: List<ClassApi>) {
        /** A hash of the public API of the entry. */
        val apiHash: String by lazy {
            val digest = MessageDigest.getInstance("SHA-256")
            // Hidden classes count too: their public members can be visible through a public subclass.
            classes.sortedBy { it.name }.forEach { c ->
                digest.update(c.declaration.toByteArray())
                c.members.sorted().forEach { digest.update(it.toByteArray()) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val allClasses: Map<String, ClassApi> =
        buildMap { this@ClasspathIndex.entries.forEach { e -> e.classes.forEach { putIfAbsent(it.name, it) } } }

    /** Public classes by name. When two entries have a class of the same name, the first on the classpath wins, as for javac. */
    val classes: Map<String, ClassApi> = allClasses.filterValues { it.isPublic }

    /**
     * The members of [api], with the members it inherits from hidden superclasses. Kotlin multifile facades such as
     * `kotlin.collections.CollectionsKt` get their functions this way.
     */
    fun membersOf(api: ClassApi): List<String> = buildList {
        addAll(api.members)
        val seen = mutableSetOf(api.name)
        var superName = api.superName
        while (superName != null && seen.add(superName)) {
            val superClass = allClasses[superName] ?: break
            if (superClass.isPublic) break
            addAll(superClass.members)
            superName = superClass.superName
        }
    }

    /** Package name to its classes, sorted. */
    val packages: Map<String, List<ClassApi>> = classes.values.groupBy { it.packageName }.toSortedMap()
        .mapValues { (_, list) -> list.sortedBy { it.name } }

    /** Package name to the labels of the entries that contain it. */
    val packageEntries: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        this@ClasspathIndex.entries.forEach { e -> e.classes.map { it.packageName }.distinct().forEach { getOrPut(it) { mutableListOf() } += e.label } }
    }

    companion object {
        /** Jars do not change while the daemon runs, so their API is read once per file version. */
        private val jarCache = ConcurrentHashMap<String, Entry>()

        fun of(files: List<File>): ClasspathIndex = ClasspathIndex(files.filter { it.exists() }.map(::entryOf))

        private fun entryOf(file: File): Entry =
            if (file.isDirectory) {
                Entry(label(file), file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.mapNotNull { read(it.readBytes()) }.toList())
            } else {
                jarCache.computeIfAbsent("${file.absolutePath}|${file.length()}|${file.lastModified()}") {
                    ZipFile(file).use { zip ->
                        val classes = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                            .mapNotNull { read(zip.getInputStream(it).use { input -> input.readBytes() }) }
                            .toList()
                        Entry(file.name, classes)
                    }
                }
            }

        /** A jar by its file name; a classes directory by its last path segments, such as `lexer/build/classes/kotlin/main`. */
        private fun label(dir: File): String = dir.absoluteFile.invariantSeparatorsPath.split('/').takeLast(5).joinToString("/")

        private fun read(bytes: ByteArray): ClassApi? = try {
            ApiVisitor().also { ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }.result()
        } catch (e: Exception) {
            null
        }
    }

    private class ApiVisitor : ClassVisitor(Opcodes.ASM9) {
        private var name = ""
        private var superName: String? = null
        private var visible = false
        private var anonymous = false
        private var kind = "class"
        private var declaration = ""
        private var isKotlin = false
        private val members = mutableListOf<String>()

        fun result(): ClassApi? = if (anonymous) {
            null
        } else {
            ClassApi(name, name.substringBeforeLast('.', ""), kind, declaration + if (isKotlin) "  // Kotlin" else "", members, isKotlin, visible, superName)
        }

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            this.name = name.replace('/', '.')
            this.superName = superName?.replace('/', '.')
            // Anonymous and local classes have a digit after the last '$'.
            anonymous = Regex("""\$\d""").containsMatchIn(this.name) || name == "module-info"
            visible = access and Opcodes.ACC_PUBLIC != 0 && access and Opcodes.ACC_SYNTHETIC == 0
            kind = when {
                access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
                access and Opcodes.ACC_INTERFACE != 0 -> "interface"
                access and Opcodes.ACC_ENUM != 0 -> "enum"
                access and Opcodes.ACC_RECORD != 0 -> "record"
                else -> "class"
            }
            val shape = if (signature != null) {
                TraceSignatureVisitor(access).also { SignatureReader(signature).accept(it) }.declaration
            } else {
                buildString {
                    if (superName != null && superName != "java/lang/Object" && kind == "class") append(" extends ").append(superName.replace('/', '.'))
                    if (!interfaces.isNullOrEmpty()) {
                        append(if (kind == "interface") " extends " else " implements ")
                        append(interfaces.joinToString(", ") { it.replace('/', '.') })
                    }
                }
            }
            val modifiers = modifiers(access and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT).inv().let {
                if (kind == "class" && access and Opcodes.ACC_ABSTRACT != 0) it or Opcodes.ACC_ABSTRACT else it
            })
            declaration = "$modifiers $kind ${this.name}$shape".trim()
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor == "Lkotlin/Metadata;") isKotlin = true
            return null
        }

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            if (!isApi(access)) return null
            if (access and Opcodes.ACC_ENUM != 0) {
                members += "enum constant $name"
            } else {
                val type = signature?.let { TraceSignatureVisitor(0).also { v -> SignatureReader(it).acceptType(v) }.declaration }
                    ?: Type.getType(descriptor).className
                members += "${modifiers(access)} field $name: $type".trim()
            }
            return null
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            if (!isApi(access) || access and Opcodes.ACC_BRIDGE != 0 || name == "<clinit>") return null
            val (parameters, returnType) = if (signature != null) {
                val v = TraceSignatureVisitor(access).also { SignatureReader(signature).accept(it) }
                v.declaration to v.returnType
            } else {
                Type.getArgumentTypes(descriptor).joinToString(", ", "(", ")") { it.className } to Type.getReturnType(descriptor).className
            }
            members += if (name == "<init>") {
                "${modifiers(access)} constructor$parameters".trim()
            } else {
                "${modifiers(access)} $name$parameters: $returnType".trim()
            }
            return null
        }

        private fun isApi(access: Int) =
            access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 && access and Opcodes.ACC_SYNTHETIC == 0

        private fun modifiers(access: Int): String = listOfNotNull(
            "public".takeIf { access and Opcodes.ACC_PUBLIC != 0 },
            "protected".takeIf { access and Opcodes.ACC_PROTECTED != 0 },
            "static".takeIf { access and Opcodes.ACC_STATIC != 0 },
            "abstract".takeIf { access and Opcodes.ACC_ABSTRACT != 0 },
            "final".takeIf { access and Opcodes.ACC_FINAL != 0 },
        ).joinToString(" ")
    }
}
