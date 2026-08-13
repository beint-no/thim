package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

class DocumentCheckerTest {
    @Test
    fun `nested forms are rejected`() {
        val problems = check(
            """
            <html><body>
            <form action="/a">
              <form action="/b"></form>
            </form>
            </body></html>
            """.trimIndent(),
        )

        assertTrue(problems.any { it.contains("THIM-FORM-NESTED") }, problems.toString())
    }

    @Test
    fun `sibling forms are allowed`() {
        val problems = check(
            """
            <html><body>
            <form action="/a"></form>
            <form action="/b"></form>
            </body></html>
            """.trimIndent(),
        )

        assertTrue(problems.none { it.contains("THIM-FORM-NESTED") }, problems.toString())
    }

    @Test
    fun `label for must match an id`() {
        val problems = check(
            """
            <html><body>
            <label for="missing">Name</label>
            </body></html>
            """.trimIndent(),
        )

        assertTrue(problems.any { it.contains("THIM-REFERENCE-UNKNOWN") && it.contains("for=") }, problems.toString())
    }

    private fun check(source: String): List<String> {
        val checker = DocumentChecker {}
        checker.check(TemplateParser("page", source).parse())
        return checker.problems
    }
}
