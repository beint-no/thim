package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RouteCatalogTest {
    private val catalog = RouteCatalog(
        listOf(
            Route(
                setOf("GET"),
                "/users/{name}",
                listOf(RouteSegment.Literal("users"), RouteSegment.Variable("name")),
            ),
        ),
        emptyList(),
    )

    @Test
    fun `dotted user names are validated as routes`() {
        catalog.check("/users/john.doe", "GET", null, "th:href")
    }

    @Test
    fun `static javascript assets are not required to match a controller`() {
        catalog.check("/assets/app.js", "GET", null, "th:src")
    }

    @Test
    fun `unknown application paths still fail`() {
        val problem = assertFailsWith<IllegalArgumentException> {
            catalog.check("/missing", "GET", null, "th:href")
        }
        assertTrue(problem.message.orEmpty().contains("THIM-URL-UNKNOWN-ROUTE"), problem.message)
    }

    @Test
    fun `repeated paths still validate each method and report the current source`() {
        catalog.check("/users/ada?tab=profile", "GET", null, "th:href")
        val location = SourceLocation("edit-user", 12, 9)
        val problem = assertFailsWith<ThimDiagnostic> {
            catalog.check("/users/ada#details", "POST", location, "th:action")
        }
        assertEquals("THIM-URL-METHOD", problem.code)
        assertEquals(location, problem.location)
        assertTrue(problem.message.orEmpty().contains("th:action"))
        catalog.check("/users/ada", "GET", null, "th:href")
    }

    @Test
    fun `repeated missing paths report the current source`() {
        assertFailsWith<ThimDiagnostic> { catalog.check("/missing", "GET", null, "th:href") }
        val location = SourceLocation("missing-form", 7, 4)
        val problem = assertFailsWith<ThimDiagnostic> {
            catalog.check("/missing", "POST", location, "th:action")
        }
        assertEquals("THIM-URL-UNKNOWN-ROUTE", problem.code)
        assertEquals(location, problem.location)
        assertTrue(problem.message.orEmpty().contains("th:action"))
    }

    @Test
    fun `enum coverage and methods are checked independently for repeated paths`() {
        val catalog = RouteCatalog(
            listOf(Route(setOf("GET"), "/READY", listOf(RouteSegment.Literal("READY")))),
            emptyList(),
        )
        catalog.check("/{status}", "GET", null, "th:href", mapOf("status" to listOf("READY")))
        val missing = assertFailsWith<ThimDiagnostic> {
            catalog.check("/{status}", "GET", null, "th:href", mapOf("status" to listOf("READY", "MISSING")))
        }
        assertEquals("THIM-URL-ENUM-ROUTE", missing.code)
        assertTrue(missing.message.orEmpty().contains("MISSING"))
        val method = assertFailsWith<ThimDiagnostic> {
            catalog.check("/{status}", "POST", null, "th:action", mapOf("status" to listOf("READY")))
        }
        assertEquals("THIM-URL-ENUM-ROUTE", method.code)
        val unknown = assertFailsWith<ThimDiagnostic> {
            catalog.check("/{status}", "GET", null, "th:href")
        }
        assertEquals("THIM-URL-UNKNOWN-ROUTE", unknown.code)
        catalog.check("/{status}", "GET", null, "th:href", mapOf("status" to listOf("READY")))
    }

}
