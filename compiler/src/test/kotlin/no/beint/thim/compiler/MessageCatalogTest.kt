package no.beint.thim.compiler

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageCatalogTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `loads nested namespaces multiline strings and typed plural arguments`() {
        write("en/home.yaml", """
            title: Thim {version}
            introduction: |-
              First line.
              Second line.
            inbox:
              _plural: unreadCount
              one: One unread message
              other: "{unreadCount} unread messages"
        """)
        write("nb/home.yaml", """
            title: Thim {version}
            introduction: |-
              Første linje.
              Andre linje.
            inbox:
              _plural: unreadCount
              one: Én ulest melding
              other: "{unreadCount} uleste meldinger"
        """)

        val catalog = MessageCatalog.load(directory, "en", listOf("en", "nb"))

        assertEquals(MessageArgumentKind.TEXT, catalog.use("home.title", setOf("version"), "test").arguments["version"])
        assertEquals(
            MessageArgumentKind.NUMBER,
            catalog.use("home.inbox", setOf("unreadCount"), "test").arguments["unreadCount"],
        )
        val introduction = catalog.use("home.introduction", emptySet(), "test")
            .values.getValue("en")
        assertEquals("First line.\nSecond line.", assertIs<MessageText>(assertIs<MessagePattern>(introduction).parts.single()).value)
    }

    @Test
    fun `failsafe schema keeps ambiguous scalars as text`() {
        write("en/values.yaml", """
            negative: no
            enabled: true
            amount: 12
            date: 2026-08-08
        """)

        val catalog = MessageCatalog.load(directory, "en", listOf("en"))

        assertText(catalog, "values.negative", "no")
        assertText(catalog, "values.enabled", "true")
        assertText(catalog, "values.amount", "12")
        assertText(catalog, "values.date", "2026-08-08")
    }

    @Test
    fun `loads select messages and nested selections`() {
        write("en/account.yaml", """
            greeting:
              _select: audience
              MEMBER: Welcome back, {name}
              other: Welcome, {name}
            inbox:
              _select: audience
              MEMBER:
                _plural: unreadCount
                one: "{name}, you have one unread message"
                other: "{name}, you have {unreadCount} unread messages"
              other: "Welcome, {name}"
        """)

        val catalog = MessageCatalog.load(directory, "en", listOf("en"))

        assertEquals(
            mapOf("audience" to MessageArgumentKind.SELECT, "name" to MessageArgumentKind.TEXT),
            catalog.use("account.greeting", setOf("audience", "name"), "test").arguments,
        )
        assertEquals(
            mapOf(
                "audience" to MessageArgumentKind.SELECT,
                "unreadCount" to MessageArgumentKind.NUMBER,
                "name" to MessageArgumentKind.TEXT,
            ),
            catalog.use("account.inbox", setOf("audience", "unreadCount", "name"), "test").arguments,
        )
    }

    @Test
    fun `rejects duplicate mapping keys`() {
        write("en/home.yaml", """
            title: First
            title: Second
        """)

        assertProblem("duplicate key 'title'") { MessageCatalog.load(directory, "en", listOf("en")) }
    }

    @Test
    fun `rejects YAML anchors tags and sequences`() {
        write("en/home.yaml", "title: &shared Hello\ncopy: *shared")
        assertProblem("anchors and aliases are not supported") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }

        write("en/home.yaml", "title: !!str Hello")
        assertProblem("YAML tags are not supported") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }

        write("en/home.yaml", "title:\n  - Hello")
        assertProblem("YAML sequences are not supported") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }
    }

    @Test
    fun `rejects short extensions and multiple documents`() {
        write("en/home.yml", "title: Hello")
        assertProblem("must use the .yaml extension") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }

        Files.delete(directory.resolve("en/home.yml"))
        write("en/home.yaml", "title: Hello\n---\ntitle: Again")
        assertProblem("expected exactly one YAML document") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }
    }

    @Test
    fun `rejects case variant extensions and catalogs outside locale directories`() {
        write("en/home.yaml", "title: Hello")
        write("en/extra.YAML", "subtitle: Welcome")

        assertProblem("must use the .yaml extension") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }

        Files.delete(directory.resolve("en/extra.YAML"))
        write("root.yaml", "title: Ignored")
        assertProblem("must be stored inside a locale directory") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }
    }

    @Test
    fun `requires the same keys and argument contract in every locale`() {
        write("en/home.yaml", "title: Hello {name}\nsubtitle: Welcome")
        write("nb/home.yaml", "title: Hei\nextra: Ekstra")

        assertProblem("locale 'nb' is missing [home.subtitle]") {
            MessageCatalog.load(directory, "en", listOf("en", "nb"))
        }

        write("nb/home.yaml", "title: Hei\nsubtitle: Velkommen")
        assertProblem("changes the argument contract; missing [name]") {
            MessageCatalog.load(directory, "en", listOf("en", "nb"))
        }
    }

    @Test
    fun `requires canonical configured locale tags`() {
        write("en/home.yaml", "title: Hello")

        assertProblem("not a canonical BCP 47 tag; use 'en-US'") {
            MessageCatalog.load(directory, "en-us", listOf("en-us"))
        }
    }

    @Test
    fun `rejects plural categories unreachable in the locale`() {
        write("en/home.yaml", """
            inbox:
              _plural: count
              few: A few messages
              other: "{count} messages"
        """)

        assertProblem("unreachable plural categories [few]") {
            MessageCatalog.load(directory, "en", listOf("en"))
        }
    }

    @Test
    fun `allows projects with no message catalog`() {
        val missing = directory.resolve("missing")
        val catalog = MessageCatalog.load(missing, "en", listOf("en"))

        assertProblem("message 'home.title' does not exist") {
            catalog.use("home.title", emptySet(), "test")
        }
    }

    private fun assertText(catalog: MessageCatalog, key: String, expected: String) {
        val value = catalog.use(key, emptySet(), "test").values.getValue("en")
        val pattern = assertIs<MessagePattern>(value)
        assertEquals(expected, assertIs<MessageText>(pattern.parts.single()).value)
    }

    private fun assertProblem(expected: String, block: () -> Unit) {
        val problem = assertFailsWith<RuntimeException>(block = block)
        assertTrue(problem.message.orEmpty().contains(expected), problem.message)
    }

    private fun write(relative: String, contents: String) {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, contents.trimIndent() + "\n")
    }
}
