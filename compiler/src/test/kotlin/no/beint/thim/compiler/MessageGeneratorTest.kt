package no.beint.thim.compiler

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class MessageGeneratorTest {
    @TempDir
    lateinit var directory: Path

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
