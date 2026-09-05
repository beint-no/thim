package no.beint.thim.compiler

import no.beint.thim.CompiledMessage
import org.junit.jupiter.api.io.TempDir
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageGeneratorTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `compiled messages preserve constants parameters references and locale fallbacks`() {
        checkCompiledMessages(localized = true)
    }

    @Test
    fun `compiled single-locale messages preserve constants and parameters`() {
        checkCompiledMessages(localized = false)
    }

    private fun checkCompiledMessages(localized: Boolean) {
        val translations = if (localized) {
            mapOf("en" to "Account", "en-GB" to "British account", "nb" to "Konto")
        } else {
            mapOf("en" to "Account")
        }
        for ((locale, title) in translations) {
            Files.createDirectories(directory.resolve(locale))
            Files.writeString(directory.resolve("$locale/account.yaml"), """
                title: $title
                empty: ""
                escaped: |-
                  Quotes " ' and backslash \ & < > æ😀
                  Second line.
                greeting: Hello {name}
                count:
                  _plural: size
                  one: One item
                  other: "{size} items"
                salutation:
                  _select: audience
                  MEMBER: Welcome back, {name}
                  other: Welcome, {name}
            """.trimIndent())
        }
        val catalog = MessageCatalog.load(directory, "en", translations.keys.toList())
        val generated = MessageGenerator(catalog).generate("example", "Messages")
        val source = directory.resolve("Messages.java")
        Files.writeString(source, generated)
        val classes = Files.createDirectory(directory.resolve("classes"))
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = StringWriter()
        compiler.getStandardFileManager(null, null, null).use { files ->
            val runtime = Path.of(CompiledMessage::class.java.protectionDomain.codeSource.location.toURI())
            assertTrue(compiler.getTask(diagnostics, files, null,
                listOf("-classpath", runtime.toString(), "-d", classes.toString()), null,
                files.getJavaFileObjects(source)).call(), diagnostics.toString())
        }
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val messages = loader.loadClass("example.Messages")
            val account = loader.loadClass("example.Messages\$Account")
            fun message(name: String, vararg arguments: Any): CompiledMessage {
                val factory = account.methods.single { it.name == name }
                return factory.invoke(null, *arguments) as CompiledMessage
            }
            val titles = mapOf("en" to "Account", "en-GB" to "British account", "en-AU" to "Account",
                "nb" to "Konto", "nb-NO" to "Konto", "fr" to "Account", "und" to "Account")
            val titleReference = account.getField("titleReference").get(null)
            for ((tag, localizedTitle) in titles) {
                val expected = if (localized) localizedTitle else "Account"
                val locale = Locale.forLanguageTag(tag)
                assertEquals(expected, message("title").resolve(locale), tag)
                assertEquals(expected, messages.getMethod("resolveReference", String::class.java, Locale::class.java)
                    .invoke(null, titleReference, locale), tag)
                assertEquals("", message("empty").resolve(locale))
                assertEquals("Quotes \" ' and backslash \\ & < > æ😀\nSecond line.", message("escaped").resolve(locale))
                assertEquals("Hello <&> æ😀", message("greeting", "<&> æ😀").resolve(locale))
                assertEquals("One item", message("count", 1L).resolve(locale))
                assertEquals("2 items", message("count", 2L).resolve(locale))
                assertEquals("Welcome back, Ada", message("salutation", "MEMBER", "Ada").resolve(locale))
                assertEquals("Welcome, Ada", message("salutation", "GUEST", "Ada").resolve(locale))
            }
            assertFailsWith<NullPointerException> { message("title").resolve(null) }
            assertFailsWith<NullPointerException> { message("empty").resolve(null) }
        }
    }

    @Test
    fun `generates compile-time references and resolution for argument-free messages`() {
        Files.createDirectories(directory.resolve("en"))
        Files.writeString(
            directory.resolve("en/account.yaml"),
            "title: Account\ngreeting: Hello {name}\n",
        )

        val generated = MessageGenerator(MessageCatalog.load(directory, "en", listOf("en")))
            .generate("example", "Messages")

        assertContains(generated, "public static final String titleReference = \"{thim:example.Messages:account.title}\";")
        assertContains(generated, "case titleReference -> title().resolve(locale);")
        assertFalse(generated.contains("greetingReference"))
    }

    @Test
    fun `records generated factories separately from actual template usage`() {
        Files.createDirectories(directory.resolve("en"))
        Files.writeString(
            directory.resolve("en/account.yaml"),
            "title: Account\ngreeting: Hello {name}\n",
        )
        val catalog = MessageCatalog.load(directory, "en", listOf("en"))
        catalog.use("account.title", emptySet(), "test")

        val manifest = MessageGenerator(catalog).usageManifest(
            packageName = "example",
            className = "Messages",
            catalogId = "app:messages",
            enforceUnused = true,
        )

        assertContains(manifest, "catalog\tYXBwOm1lc3NhZ2Vz")
        assertContains(manifest, "definition\tYWNjb3VudC5ncmVldGluZw\texample/Messages\$Account\tgreeting")
        assertContains(manifest, "definition\tYWNjb3VudC50aXRsZQ\texample/Messages\$Account\ttitle")
        assertContains(manifest, "template\tYWNjb3VudC50aXRsZQ")
        assertFalse(manifest.contains("template\tYWNjb3VudC5ncmVldGluZw"))
    }
}
