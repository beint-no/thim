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

        assertContains(generated, "public static final String titleReference = \"{thim:account.title}\";")
        assertContains(generated, "case titleReference -> title().resolve(locale);")
        assertFalse(generated.contains("greetingReference"))
    }
}
