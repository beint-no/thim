package no.beint.thim.compiler

internal class RouteGenerator(private val catalog: RouteCatalog) {
    fun generate(packageName: String, objectName: String): String {
        val routes = catalog.routes
            .groupBy(Route::pattern)
            .map { (pattern, mappings) -> merged(pattern, mappings) }
            .sortedBy(Route::pattern)
        val names = functionNames(routes)

        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import no.beint.thim.UrlEncoding")
            appendLine()
            appendLine("public object $objectName {")
            routes.forEach { route -> appendFunction(names.getValue(route.pattern), route) }
            appendLine("}")
        }
    }

    private fun merged(pattern: String, mappings: List<Route>): Route {
        val segments = mappings.first().segments
        val pathParameters = segments.mapNotNull { segment ->
            val name = when (segment) {
                is RouteSegment.Variable -> segment.name
                is RouteSegment.TailWildcard -> segment.name ?: "tail"
                is RouteSegment.Literal -> return@mapNotNull null
            }
            if (segment is RouteSegment.TailWildcard) {
                RouteParameter(name, "kotlin.collections.List<kotlin.String>")
            } else {
                val candidates = mappings.flatMap(Route::pathParameters).filter { it.name == name }.distinct()
                require(candidates.size <= 1) {
                    "Cannot generate route '$pattern': path variable '$name' has conflicting types"
                }
                candidates.singleOrNull() ?: RouteParameter(name, "kotlin.String")
            }
        }
        val queryParameters = mappings.flatMap(Route::queryParameters)
            .groupBy(RouteParameter::name)
            .map { (name, candidates) ->
                val distinct = candidates.distinct()
                require(distinct.size == 1) {
                    "Cannot generate route '$pattern': query parameter '$name' has conflicting types"
                }
                distinct.single()
            }
            .sortedBy(RouteParameter::name)
        return Route(mappings.flatMap { it.httpMethods }.toSet(), pattern, segments, pathParameters, queryParameters)
    }

    private fun functionNames(routes: List<Route>): Map<String, String> {
        val bases = routes.associate { it.pattern to baseName(it) }
        val candidates = routes.associate { route ->
            val base = bases.getValue(route.pattern)
            val collides = bases.values.count { it == base } > 1
            val variables = route.segments.mapNotNull {
                when (it) {
                    is RouteSegment.Variable -> it.name
                    is RouteSegment.TailWildcard -> it.name ?: "tail"
                    is RouteSegment.Literal -> null
                }
            }
            route.pattern to if (collides && variables.isNotEmpty()) {
                base + variables.joinToString("") { "By${camelWord(it)}" }
            } else {
                base
            }
        }
        return candidates.entries.groupBy(Map.Entry<String, String>::value).values.flatMap { group ->
            if (group.size == 1) {
                listOf(group.single().key to group.single().value)
            } else {
                group.sortedBy(Map.Entry<String, String>::key).mapIndexed { index, entry ->
                    entry.key to "${entry.value}Path${index + 1}"
                }
            }
        }.toMap()
    }

    private fun baseName(route: Route): String {
        val words = route.segments.filterIsInstance<RouteSegment.Literal>().flatMap { words(it.value) }
        if (words.isEmpty()) return "root"
        return words.first().lowercase() + words.drop(1).joinToString("") { camelWord(it) }
    }

    private fun StringBuilder.appendFunction(name: String, route: Route) {
        val names = parameterNames(route)
        val parameters = route.pathParameters.map { "${identifier(names.path.getValue(it.name))}: ${it.type}" } +
            route.queryParameters.map { "${identifier(names.query.getValue(it.name))}: ${it.type}? = null" } +
            "${names.additionalQuery}: kotlin.collections.Map<kotlin.String, kotlin.Any?> = emptyMap()"
        appendLine("    public fun ${identifier(name)}(")
        parameters.forEach { appendLine("        $it,") }
        appendLine("    ): String {")
        appendLine("        val url = StringBuilder()")
        if (route.segments.isEmpty()) {
            appendLine("        url.append('/')")
        } else {
            route.segments.forEach { segment ->
                when (segment) {
                    is RouteSegment.Literal -> appendLine("        url.append(\"/${escape(segment.value)}\")")
                    is RouteSegment.Variable -> appendLine(
                        "        url.append('/').append(UrlEncoding.pathSegment(${identifier(names.path.getValue(segment.name))}))",
                    )
                    is RouteSegment.TailWildcard -> {
                        val name = identifier(names.path.getValue(segment.name ?: "tail"))
                        appendLine("        $name.forEach { url.append('/').append(UrlEncoding.pathSegment(it)) }")
                    }
                }
            }
        }
        route.queryParameters.forEach {
            appendLine(
                "        UrlEncoding.appendQuery(url, \"${escape(it.name)}\", ${identifier(names.query.getValue(it.name))})",
            )
        }
        appendLine("        ${names.additionalQuery}.forEach { (name, value) -> UrlEncoding.appendQuery(url, name, value) }")
        appendLine("        return url.toString()")
        appendLine("    }")
        appendLine()
    }

    private fun words(value: String): List<String> = value.split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)

    private fun camelWord(value: String): String = words(value).joinToString("") {
        it.lowercase().replaceFirstChar(Char::uppercaseChar)
    }

    private fun identifier(value: String): String = "`$value`"

    private fun parameterNames(route: Route): ParameterNames {
        val used = mutableSetOf<String>()
        fun unique(wireName: String): String {
            val base = kotlinName(wireName)
            return generateSequence(base) { candidate -> "${candidate}_" }.first(used::add)
        }
        val path = route.pathParameters.associate { it.name to unique(it.name) }
        val query = route.queryParameters.associate { it.name to unique(it.name) }
        return ParameterNames(path, query, unique("additionalQueryParameters"))
    }

    private fun kotlinName(value: String): String {
        val sanitized = value.map { if (it == '_' || it.isLetterOrDigit()) it else '_' }.joinToString("")
        return when {
            sanitized.isEmpty() -> "parameter"
            sanitized.first() == '_' || sanitized.first().isLetter() -> sanitized
            else -> "_$sanitized"
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")

    private data class ParameterNames(
        val path: Map<String, String>,
        val query: Map<String, String>,
        val additionalQuery: String,
    )
}
