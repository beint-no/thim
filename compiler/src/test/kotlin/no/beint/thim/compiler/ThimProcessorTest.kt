package no.beint.thim.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThimProcessorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `allows message-only modules when messages directory exists without templates directory`() {
        val messages = directory.resolve("src/main/resources/i18n")
        val en = messages.resolve("en")
        Files.createDirectories(en)
        Files.writeString(en.resolve("email.yaml"), "subject: Welcome {name}\n", StandardCharsets.UTF_8)
        val templates = directory.resolve("src/main/resources/templates")

        val codeGenerator = RecordingCodeGenerator()
        val logger = RecordingLogger()
        val processor = ThimProcessorProvider().create(
            SymbolProcessorEnvironment(
                options = mapOf(
                    "thim.templates" to templates.toString(),
                    "thim.messages" to messages.toString(),
                    "thim.package" to "sample.generated",
                    "thim.registry" to "SampleTemplates",
                ),
                kotlinVersion = KotlinVersion.CURRENT,
                codeGenerator = codeGenerator,
                logger = logger,
            ),
        )

        processor.process(unusedResolver())

        assertTrue(logger.errors.isEmpty(), logger.errors.toString())
        val generated = codeGenerator.written()
        assertTrue("sample/generated/SampleMessages.java" in generated, generated.keys.toString())
        assertTrue(
            "META-INF/thim/messages/sample_generated_SampleMessages.usage" in generated,
            generated.keys.toString(),
        )
        assertTrue(generated.getValue("sample/generated/SampleMessages.java").contains("class SampleMessages"))
    }

    @Test
    fun `fails when neither templates nor messages directory exists`() {
        val templates = directory.resolve("missing/templates")
        val messages = directory.resolve("missing/i18n")

        val codeGenerator = RecordingCodeGenerator()
        val logger = RecordingLogger()
        val processor = ThimProcessorProvider().create(
            SymbolProcessorEnvironment(
                options = mapOf(
                    "thim.templates" to templates.toString(),
                    "thim.messages" to messages.toString(),
                ),
                kotlinVersion = KotlinVersion.CURRENT,
                codeGenerator = codeGenerator,
                logger = logger,
            ),
        )

        processor.process(unusedResolver())

        assertEquals(1, logger.errors.size, logger.errors.toString())
        assertTrue(logger.errors.single().contains("Template directory does not exist"), logger.errors.toString())
    }

    private fun unusedResolver(): Resolver = Proxy.newProxyInstance(
        Resolver::class.java.classLoader,
        arrayOf(Resolver::class.java),
    ) { _, method, _ -> error("Unexpected Resolver call: ${method.name}") } as Resolver

    private class RecordingCodeGenerator : CodeGenerator {
        private val files = linkedMapOf<String, ByteArrayOutputStream>()

        override val generatedFile: Collection<File> = emptyList()

        fun written(): Map<String, String> = files.mapValues { (_, out) -> out.toString(StandardCharsets.UTF_8) }

        override fun createNewFile(
            dependencies: Dependencies,
            packageName: String,
            fileName: String,
            extensionName: String,
        ): OutputStream {
            val prefix = if (packageName.isEmpty()) "" else packageName.replace('.', '/') + "/"
            val suffix = if (extensionName.isEmpty()) "" else ".$extensionName"
            return files.getOrPut("$prefix$fileName$suffix") { ByteArrayOutputStream() }
        }

        override fun createNewFileByPath(
            dependencies: Dependencies,
            path: String,
            extensionName: String,
        ): OutputStream {
            val suffix = if (extensionName.isEmpty()) "" else ".$extensionName"
            return files.getOrPut("$path$suffix") { ByteArrayOutputStream() }
        }

        override fun associate(sources: List<KSFile>, packageName: String, fileName: String, extensionName: String) {}
        override fun associateByPath(sources: List<KSFile>, path: String, extensionName: String) {}
        override fun associateWithClasses(
            classes: List<KSClassDeclaration>,
            packageName: String,
            fileName: String,
            extensionName: String,
        ) {}
    }

    private class RecordingLogger : KSPLogger {
        val errors = mutableListOf<String>()

        override fun logging(message: String, symbol: KSNode?) {}
        override fun info(message: String, symbol: KSNode?) {}
        override fun warn(message: String, symbol: KSNode?) {}
        override fun error(message: String, symbol: KSNode?) {
            errors += message
        }
        override fun exception(e: Throwable) {
            errors += e.toString()
        }
    }
}
