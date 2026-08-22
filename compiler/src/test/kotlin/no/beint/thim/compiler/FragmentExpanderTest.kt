package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FragmentExpanderTest {
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
