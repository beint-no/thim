package no.beint.thim.compiler

import no.beint.thim.HtmlOutput
import no.beint.thim.RenderContext
import no.beint.thim.TemplateSet
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.CodeAttribute
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryGeneratorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `large registries dispatch every model through JIT-compilable methods`() {
        val models = 600
        val source = Files.createDirectories(directory.resolve("src/example"))
        val renderers = buildString {
            appendLine("package example;")
            appendLine("final class Renderers {")
            (0..models).forEach { index ->
                val model = if (index == models) "Open" else "M$index"
                appendLine("    static final class R$index {")
                appendLine("        static void render($model model, no.beint.thim.RenderContext context, no.beint.thim.HtmlOutput output) throws java.io.IOException {")
                appendLine("            output.text($index);")
                appendLine("        }")
                appendLine("    }")
            }
            appendLine("}")
        }
        Files.writeString(source.resolve("Renderers.java"), renderers)
        Files.writeString(source.resolve("Models.java"), buildString {
            appendLine("package example;")
            appendLine("interface Page {}")
            (0 until models).forEach { appendLine("final class M$it implements Page {}") }
            appendLine("class Open {}")
            appendLine("final class OpenChild extends Open {}")
        })
        val entries = (0 until models).map { RegistryEntry("example.M$it", "Renderers.R$it", it % 2 == 0) } +
            RegistryEntry("example.Open", "Renderers.R$models", false)
        Files.writeString(source.resolve("Registry.java"), RegistryGenerator(entries).generate("example", "Registry"))

        val classes = Files.createDirectory(directory.resolve("classes"))
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = StringWriter()
        compiler.getStandardFileManager(null, null, null).use { files ->
            val runtime = Path.of(TemplateSet::class.java.protectionDomain.codeSource.location.toURI())
            val sources = listOf("Renderers.java", "Models.java", "Registry.java").map(source::resolve)
            assertTrue(compiler.getTask(diagnostics, files, null,
                listOf("-classpath", runtime.toString(), "-d", classes.toString()), null,
                files.getJavaFileObjects(*sources.toTypedArray())).call(), diagnostics.toString())
        }

        // Methods on the per-request path; the one-time index initializer and the subclass fallback may grow.
        val hot = ClassFile.of().parse(classes.resolve("example/Registry.class")).methods().filter {
            val name = it.methodName().stringValue()
            name in setOf("supports", "supportsReturnType", "usesRequestDataValues", "render") || name.matches(Regex("render\\d+"))
        }
        assertEquals(7, hot.size)
        hot.forEach { method ->
            val length = (method.code().orElseThrow() as CodeAttribute).codeLength()
            assertTrue(length < 8_000, "${method.methodName().stringValue()} has $length bytes of bytecode")
        }

        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val registry = loader.loadClass("example.Registry").getConstructor().newInstance() as TemplateSet
            fun render(model: Any): String {
                val bytes = ByteArrayOutputStream()
                val output = HtmlOutput(bytes)
                registry.render(model, RenderContext(Locale.ENGLISH, ""), output)
                output.flush()
                return bytes.toString(Charsets.UTF_8)
            }
            fun instance(name: String): Any = loader.loadClass("example.$name")
                .getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            (0 until models).forEach { index ->
                val model = instance("M$index")
                assertEquals(index.toString(), render(model))
                assertTrue(registry.supports(model.javaClass))
                assertEquals(index % 2 == 0, registry.usesRequestDataValues(model.javaClass))
            }
            val child = instance("OpenChild")
            assertEquals(models.toString(), render(child))
            assertFalse(registry.supports(child.javaClass))
            assertTrue(registry.supportsReturnType(loader.loadClass("example.Open")))
            assertTrue(registry.supportsReturnType(loader.loadClass("example.Page")))
            assertFalse(registry.supportsReturnType(Any::class.java))
            assertFalse(registry.supportsReturnType(String::class.java))
            val failure = assertFailsWith<IllegalArgumentException> { render("not a page") }
            assertEquals("No compiled template for java.lang.String", failure.message)
        }
    }
}
