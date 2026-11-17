package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentCatalogTest {
    private fun parsed(vararg sources: Pair<String, String>) = sources.associate { (name, source) -> name to TemplateParser(name, source).parse() }
    private fun expand(definition: String, call: String): List<Node> {
        val sources = parsed("components" to definition, "page" to call)
        return FragmentExpander(sources).expand("page", sources.getValue("page"))
    }
    private fun rejects(code: String, definition: String, call: String) {
        val failure = assertFailsWith<IllegalArgumentException> { expand(definition, call) }
        assertTrue(failure.message.orEmpty().contains("THIM-COMPONENT-$code"), failure.message)
    }

    @Test fun `component calls disappear and preserve typed props`() {
        val root = expand("""<button th:component="ui:button" th:props="sample.Button" th:disabled="${'$'}{disabled}"><th:block th:slot="default" required/></button>""",
            """<ui:button th:props="${'$'}{save}">Save</ui:button>""").single() as ElementNode
        assertEquals(ComponentBinding("ui:button", "sample.Button", "${'$'}{save}"), root.component)
        val button = root.children.single() as ElementNode
        assertEquals("button", button.name)
        assertEquals(setOf("th:disabled"), button.attributes.keys)
        val slot = button.children.single() as ElementNode
        assertTrue(slot.callerContent)
        assertEquals<List<Node>>(listOf(RawNode("Save")), slot.children)
    }

    @Test fun `named slots preserve caller content and fallback stays in component scope`() {
        val root = expand("""<section th:component="ui:card"><header><th:block th:slot="heading">Fallback</th:block></header><th:block th:slot="default"/></section>""",
            """<ui:card><th:block th:fill="heading"><h2>Title</h2></th:block><p>Body</p></ui:card>""").single()
        val elements = root.elements().toList()
        assertEquals(2, elements.count { it.callerContent })
        assertEquals(1, elements.count { it.name == "h2" })
        assertEquals(1, elements.count { it.name == "p" })
        val fallback = expand("""<div th:component="ui:card"><th:block th:slot="heading">Fallback</th:block></div>""", "<ui:card/>")
        assertTrue(fallback.asSequence().flatMap(Node::elements).none { it.callerContent })
    }

    @Test fun `loop and condition stay outside component props scope`() {
        val root = expand("""<b th:component="ui:item" th:props="sample.Item" th:text="${'$'}{name}"/>""",
            """<ui:item th:each="item : ${'$'}{items}" th:if="${'$'}{item.visible}" th:props="${'$'}{item}"/>""").single() as ElementNode
        assertEquals("item : ${'$'}{items}", root.attributes["th:each"])
        assertEquals("${'$'}{item.visible}", root.attributes["th:if"])
    }

    @Test fun `components and fragments compose in both directions`() {
        val source = parsed("lib" to """<div th:fragment="label(text)" th:text="${'$'}{text}"/><section th:component="ui:card" th:props="sample.Card"><span th:replace="~{lib :: label(${'$'}{title})}"/></section><th:block th:fragment="wrapper(card)"><ui:card th:props="${'$'}{card}"/></th:block>""",
            "page" to """<div th:replace="~{lib :: wrapper(${'$'}{value})}"/>""")
        val expander = FragmentExpander(source)
        val result = expander.expand("page", source.getValue("page"))
        val component = result.asSequence().flatMap(Node::elements).first { it.component != null }
        assertEquals("${'$'}{value}", component.component?.expression)
        assertTrue(expander.unusedComponents().isEmpty())
        assertTrue(expander.unusedFragments().isEmpty())
    }

    @Test fun `repeated expansions do not mutate definitions or share slots`() {
        val source = parsed("lib" to """<div th:component="ui:card"><th:block th:slot="default"/></div>""", "page" to "<ui:card>First</ui:card><ui:card>Second</ui:card>")
        val before = source.toString()
        val expander = FragmentExpander(source)
        val first = expander.expand("page", source.getValue("page"))
        assertEquals(first, expander.expand("page", source.getValue("page")))
        assertEquals(before, source.toString())
        assertEquals(listOf("First", "Second"), first.map { node -> node.elements().last().children.single().let { (it as RawNode).value } })
    }

    @Test fun `definitions must have unique valid names and qualified props`() {
        rejects("NAME", """<div th:component="card"/>""", "<ui:card/>")
        rejects("NAME", """<div th:component="th:card"/>""", "<th:card/>")
        rejects("DUPLICATE", """<div th:component="ui:card"/><p th:component="ui:card"/>""", "<ui:card/>")
        rejects("PROPS", """<div th:component="ui:card" th:props="Card?"/>""", "<ui:card/>")
        rejects("DEFINITION", """<div th:component="ui:card" th:fragment="card"/>""", "<ui:card/>")
        rejects("DEFINITION", """<div th:component="ui:card"><p th:component="ui:inner"/></div>""", "<ui:card/>")
    }

    @Test fun `unknown components and unsupported call attributes fail`() {
        rejects("UNKNOWN", "", "<ui:missing/>")
        rejects("ATTRIBUTE", """<div th:component="ui:card"/>""", """<ui:card class="ignored"/>""")
        rejects("ATTRIBUTE", """<div th:component="ui:card"/>""", """<ui:card th:text="${'$'}{name}"/>""")
    }

    @Test fun `props are required exactly when declared`() {
        rejects("PROPS", """<div th:component="ui:card" th:props="sample.Card"/>""", "<ui:card/>")
        rejects("PROPS", """<div th:component="ui:card"/>""", """<ui:card th:props="${'$'}{card}"/>""")
        rejects("PROPS", """<div th:component="ui:card" th:props="sample.Card"/>""", """<ui:card th:props="literal"/>""")
    }

    @Test fun `slots reject duplicates unknown fills and missing required content`() {
        rejects("SLOT", """<div th:component="ui:card"><th:block th:slot="a"/><th:block th:slot="a"/></div>""", "<ui:card/>")
        rejects("SLOT", """<div th:component="ui:card"><span th:slot="a"/></div>""", "<ui:card/>")
        rejects("SLOT", """<div th:component="ui:card"><th:block th:slot="a" required>Fallback</th:block></div>""", "<ui:card/>")
        rejects("SLOT-REQUIRED", """<div th:component="ui:card"><th:block th:slot="default" required/></div>""", "<ui:card/>")
        rejects("FILL", """<div th:component="ui:card"/>""", "<ui:card>Ignored</ui:card>")
        rejects("FILL", """<div th:component="ui:card"/>""", """<ui:card><th:block th:fill="missing"/></ui:card>""")
        rejects("FILL", """<div th:component="ui:card"><th:block th:slot="a"/></div>""", """<ui:card><th:block th:fill="a"/><th:block th:fill="a"/></ui:card>""")
    }

    @Test fun `orphaned composition directives fail instead of disappearing`() {
        rejects("CONTEXT", "", """<th:block th:slot="default"/>""")
        rejects("CONTEXT", "", """<th:block th:fill="heading"/>""")
    }

    @Test fun `slots cannot silently disappear under content replacing directives`() {
        for (directive in listOf("th:text", "th:utext", "th:errors", "th:field", "th:replace")) {
            rejects("SLOT", """<div th:component="ui:card" $directive="ignored"><th:block th:slot="default"/></div>""", "<ui:card>Content</ui:card>")
            rejects("SLOT", """<div th:component="ui:card"><p $directive="ignored"><th:block th:slot="default"/></p></div>""", "<ui:card>Content</ui:card>")
        }
    }

    @Test fun `recursive components and mixed fragment cycles fail`() {
        rejects("RECURSIVE", """<div th:component="ui:card"><ui:card/></div>""", "<ui:card/>")
        rejects("RECURSIVE", """<div th:component="ui:a"><ui:b/></div><div th:component="ui:b"><ui:a/></div>""", "<ui:a/>")
        rejects("RECURSIVE", """<div th:component="ui:card"><th:block th:replace="~{components :: cycle}"/></div><th:block th:fragment="cycle"><ui:card/></th:block>""", "<ui:card/>")
    }

    @Test fun `same component can nest through finite caller content`() {
        val result = expand("""<div th:component="ui:card"><th:block th:slot="default"/></div>""", "<ui:card><ui:card>Nested</ui:card></ui:card>")
        assertEquals(2, result.asSequence().flatMap(Node::elements).count { it.component != null })
    }

    @Test fun `unused definitions are reported`() {
        assertEquals(listOf("ui:unused"), FragmentExpander(parsed("lib" to """<div th:component="ui:unused"/>""")).unusedComponents())
    }

    @Test fun `document checks run when a component renders the document root`() {
        val nodes = expand("""<html th:component="ui:document"><body><th:block th:slot="default"/></body></html>""",
            """<ui:document><p id="duplicate"/><p id="duplicate"/><label for="missing"/><form><form/></form></ui:document>""")
        val checker = DocumentChecker {}
        checker.check(nodes)
        for (code in listOf("THIM-ID-DUPLICATE", "THIM-REFERENCE-UNKNOWN", "THIM-FORM-NESTED")) {
            assertTrue(checker.problems.any { code in it }, checker.problems.toString())
        }
    }

    @Test fun `large expansion stays linear in output size`() {
        val definition = """<section th:component="ui:card" th:props="sample.Card"><h2 th:text="${'$'}{title}"/><th:block th:slot="default"/></section>"""
        val call = """<ui:card th:props="${'$'}{card}"><p>Content</p></ui:card>"""
        for (size in listOf(1, 100, 1000)) {
            val result = expand(definition, call.repeat(size))
            assertEquals(size, result.size)
            assertEquals(size * 5, result.asSequence().flatMap(Node::elements).count())
        }
    }
}
