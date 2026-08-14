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

    @Test
    fun `htmx hash targets must match an id`() {
        val problems = check(
            """
            <html><body>
            <button hx-target="#missing">Load</button>
            <div id="inbox"></div>
            <button hx-target="#inbox">Ok</button>
            <button hx-target="closest article">Relative</button>
            </body></html>
            """.trimIndent(),
        )

        assertTrue(problems.any { it.contains("THIM-REFERENCE-UNKNOWN") && it.contains("hx-target=\"#missing\"") }, problems.toString())
        assertTrue(problems.none { it.contains("hx-target=\"#inbox\"") }, problems.toString())
        assertTrue(problems.none { it.contains("closest") }, problems.toString())
    }

    private fun check(source: String): List<String> {
        val checker = DocumentChecker {}
        checker.check(TemplateParser("page", source).parse())
        return checker.problems
    }
}
