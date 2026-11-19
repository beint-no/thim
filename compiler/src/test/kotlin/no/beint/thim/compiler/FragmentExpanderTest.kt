package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FragmentExpanderTest {
    @Test
    fun `fragment bindings stay independent across repeated expansions`() {
        val templates = mapOf(
            "card" to TemplateParser("card", """<div th:fragment="card(title)" class="card"><span th:text="${'$'}{title}"></span></div>""").parse(),
            "home" to TemplateParser("home", """<div th:replace="~{card :: card(${'$'}{first})}"></div><div th:replace="~{card :: card(${'$'}{second})}"></div>""").parse(),
            "plain" to TemplateParser("plain", """<p class="plain" th:text="${'$'}{title}"></p>""").parse(),
        )
        val expander = FragmentExpander(templates)
        repeat(2) {
            val cards = expander.expand("home", templates.getValue("home")).filterIsInstance<ElementNode>()
            assertEquals(listOf("card", "card"), cards.map { it.attributes["class"] })
            assertEquals(listOf("${'$'}{first}", "${'$'}{second}"), cards.map {
                it.children.filterIsInstance<ElementNode>().single().attributes["th:text"]
            })
            assertEquals(templates.getValue("plain"), expander.expand("plain", templates.getValue("plain")))
        }
        assertTrue(expander.unusedParameters().isEmpty())
    }

    @Test
    fun `duplicate fragment names in one file fail compilation`() {
        val templates = mapOf(
            "cards" to TemplateParser(
                "cards",
                """
                <div th:fragment="card">A</div>
                <div th:fragment="card">B</div>
                """.trimIndent(),
            ).parse(),
        )

        val problem = assertFailsWith<IllegalArgumentException> {
            FragmentExpander(templates)
        }
        assertTrue(problem.message.orEmpty().contains("THIM-FRAGMENT-DUPLICATE"), problem.message)
    }

    @Test
    fun `used fragment reports parameters that never appear in the body`() {
        val templates = mapOf(
            "cards" to TemplateParser(
                "cards",
                """<div th:fragment="card(title, unused)"><span th:text="${'$'}{title}"></span></div>""",
            ).parse(),
            "home" to TemplateParser(
                "home",
                """<div th:replace="~{cards :: card(${'$'}{name}, ${'$'}{extra})}"></div>""",
            ).parse(),
        )

        val expander = FragmentExpander(templates)
        expander.expand("home", templates.getValue("home"))

        assertTrue(expander.unusedParameters().single().contains("unused"), expander.unusedParameters().toString())
        assertTrue(expander.unusedFragments().isEmpty(), expander.unusedFragments().toString())
    }

    @Test
    fun `preserves th if th unless and th each on th replace elements`() {
        val templates = mapOf(
            "card" to TemplateParser(
                "card",
                """<article th:fragment="card(label)" class="card"><span th:text="${'$'}{label}"></span></article>""",
            ).parse(),
            "page" to TemplateParser(
                "page",
                """
                <div class="placeholder" th:if="${'$'}{show}" th:replace="~{card :: card(${'$'}{title})}"></div>
                <div th:unless="${'$'}{hidden}" th:replace="~{card :: card(${'$'}{subtitle})}"></div>
                <div th:each="item : ${'$'}{items}" th:if="${'$'}{item.active}" th:replace="~{card :: card(${'$'}{item.name})}"></div>
                """.trimIndent(),
            ).parse(),
        )

        val expanded = FragmentExpander(templates)
            .expand("page", templates.getValue("page"))
            .filterIsInstance<ElementNode>()

        assertEquals(3, expanded.size)
        assertEquals("th:block", expanded[0].name)
        assertEquals(mapOf<String, String?>("th:if" to "${'$'}{show}"), expanded[0].attributes)
        assertEquals(
            "${'$'}{title}",
            expanded[0].children.filterIsInstance<ElementNode>().single()
                .children.filterIsInstance<ElementNode>().single().attributes["th:text"],
        )

        assertEquals("th:block", expanded[1].name)
        assertEquals(mapOf<String, String?>("th:unless" to "${'$'}{hidden}"), expanded[1].attributes)
        assertEquals(
            "${'$'}{subtitle}",
            expanded[1].children.filterIsInstance<ElementNode>().single()
                .children.filterIsInstance<ElementNode>().single().attributes["th:text"],
        )

        assertEquals("th:block", expanded[2].name)
        assertEquals(
            mapOf<String, String?>("th:each" to "item : ${'$'}{items}", "th:if" to "${'$'}{item.active}"),
            expanded[2].attributes,
        )
        assertEquals(
            "${'$'}{item.name}",
            expanded[2].children.filterIsInstance<ElementNode>().single()
                .children.filterIsInstance<ElementNode>().single().attributes["th:text"],
        )
    }

    @Test
    fun `substitutes outer bindings into th replace control directives while respecting th each shadowing`() {
        val templates = mapOf(
            "leaf" to TemplateParser("leaf", """<span th:fragment="leaf(v)" th:text="${'$'}{v}"></span>""").parse(),
            "outer" to TemplateParser(
                "outer",
                """<div th:fragment="list(item, visible)" th:each="item : ${'$'}{item.children}" th:if="${'$'}{visible}" th:replace="~{leaf :: leaf(${'$'}{item.title})}"></div>""",
            ).parse(),
            "page" to TemplateParser(
                "page",
                """<th:block th:replace="~{outer :: list(${'$'}{root}, ${'$'}{showList})}"></th:block>""",
            ).parse(),
        )

        val block = FragmentExpander(templates)
            .expand("page", templates.getValue("page"))
            .filterIsInstance<ElementNode>()
            .single()

        assertEquals("th:block", block.name)
        assertEquals("item : ${'$'}{root.children}", block.attributes["th:each"])
        assertEquals("${'$'}{showList}", block.attributes["th:if"])
        val span = block.children.filterIsInstance<ElementNode>().single()
        assertEquals("${'$'}{item.title}", span.attributes["th:text"])
    }

    @Test
    fun `rejects unexpected th directives on th replace elements`() {
        val templates = mapOf(
            "card" to TemplateParser("card", """<div th:fragment="card">Hi</div>""").parse(),
            "badText" to TemplateParser("badText", """<div th:text="${'$'}{title}" th:replace="~{card :: card}"></div>""").parse(),
            "badClass" to TemplateParser("badClass", """<div th:class="'extra'" th:replace="~{card :: card}"></div>""").parse(),
        )

        for (name in listOf("badText", "badClass")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                FragmentExpander(templates).expand(name, templates.getValue(name))
            }
            assertTrue(failure.message.orEmpty().contains("THIM-REPLACE-ATTRIBUTE"), failure.message)
        }
    }

    @Test
    fun `preserves dollar signs and backslashes in fragment argument substitutions`() {
        val templates = mapOf(
            "badge" to TemplateParser(
                "badge",
                """<span th:fragment="badge(prefix)" th:data-value="${'$'}{prefix}"></span>""",
            ).parse(),
            "page" to TemplateParser(
                "page",
                """<div th:replace="~{badge :: badge(${'$'}{price})}"></div>""",
            ).parse(),
            "nested" to TemplateParser(
                "nested",
                """<span th:fragment="pair(a, b)" th:data-a="${'$'}{a}" th:data-b="${'$'}{b}"></span>""",
            ).parse(),
            "special" to TemplateParser(
                "special",
                """<div th:replace="~{nested :: pair('$100', 'a\\b')}"></div>""",
            ).parse(),
            "exprSpecial" to TemplateParser(
                "exprSpecial",
                """<span th:fragment="wrap(item)" th:text="${'$'}{item.label}"></span>""",
            ).parse(),
            "exprCall" to TemplateParser(
                "exprCall",
                """<div th:replace="~{exprSpecial :: wrap(${'$'}{dollar${'$'}1\path})}"></div>""",
            ).parse(),
        )

        val expander = FragmentExpander(templates)
        val specialSpan = expander.expand("special", templates.getValue("special")).filterIsInstance<ElementNode>().single()
        assertEquals("'$100'", specialSpan.attributes["th:data-a"])
        assertEquals("'a\\\\b'", specialSpan.attributes["th:data-b"])

        val exprSpan = expander.expand("exprCall", templates.getValue("exprCall")).filterIsInstance<ElementNode>().single()
        assertEquals("${'$'}{dollar${'$'}1\\path.label}", exprSpan.attributes["th:text"])
    }
}
