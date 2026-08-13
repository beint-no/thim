package no.beint.thim.compiler

import kotlin.test.Test
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
}
