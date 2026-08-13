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
}
