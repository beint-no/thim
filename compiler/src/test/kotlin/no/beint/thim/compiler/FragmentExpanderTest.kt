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
}
