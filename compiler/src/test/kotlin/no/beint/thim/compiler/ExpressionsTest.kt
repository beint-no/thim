package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionsTest {
    @Test
    fun `message arguments are named`() {
        val expression = Expressions.message(
            "#{home.title(version=\${version}, userName=\${user.name})}",
            "test",
        )

        assertEquals("home.title", expression.key)
        assertEquals(listOf("version", "userName"), expression.arguments.keys.toList())
        assertEquals(listOf("user", "name"), expression.arguments.getValue("userName").segments.map { it.name })
    }

    @Test
    fun `positional message arguments are rejected`() {
        val problem = assertFailsWith<IllegalArgumentException> {
            Expressions.message("#{home.title(\${version})}", "test")
        }

        assertTrue(problem.message.orEmpty().contains("must use name=\${property}"))
    }

    @Test
    fun `duplicate message arguments are rejected`() {
        val problem = assertFailsWith<IllegalArgumentException> {
            Expressions.message("#{home.title(version=\${first}, version=\${second})}", "test")
        }

        assertTrue(problem.message.orEmpty().contains("duplicate message argument 'version'"))
    }
}
