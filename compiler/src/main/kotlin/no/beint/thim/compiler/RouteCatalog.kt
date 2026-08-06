package no.beint.thim.compiler

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

internal sealed interface RouteSegment {
    data class Literal(val value: String) : RouteSegment
    data object Variable : RouteSegment
    data object TailWildcard : RouteSegment
}

internal data class Route(val httpMethods: Set<String>, val pattern: String, val segments: List<RouteSegment>)

internal class RouteCatalog(
    private val routes: List<Route>,
    trustedPaths: List<String>,
    /**
     * Source files the routes were extracted from. They must be declared as dependencies
     * of the generated output, or incremental KSP runs rebuild the route table from only
     * the dirty files and report spurious unknown routes.
     */
    val files: List<KSFile> = emptyList(),
) {
    private val trustedPatterns: List<List<RouteSegment>> = trustedPaths.map(::trustedPattern)

    fun isEmpty(): Boolean = routes.isEmpty()

    fun check(
        path: String,
        httpMethod: String,
        location: SourceLocation?,
        subject: String,
        enumVariables: Map<String, List<String>> = emptyMap(),
    ) {
        if (routes.isEmpty()) return
        val plain = path.substringBefore('?').substringBefore('#')
        if (!plain.startsWith('/')) return
        val parts = pathParts(plain)
        if (trustedPatterns.any { matches(it, parts) }) return
        if (parts.lastOrNull()?.let { !it.variable && '.' in it.value } == true) return
        val matching = routes.filter { matches(it.segments, parts) }
        if (matching.isEmpty() && enumVariables.isNotEmpty()) {
            checkEnumExpansion(parts, enumVariables, httpMethod, location, subject)
            return
        }
        requireDiagnostic(matching.isNotEmpty(), "THIM-URL-UNKNOWN-ROUTE", location) {
            "$subject: no controller mapping matches '$plain'; known routes: ${known()}"
        }
        requireDiagnostic(
            matching.any { it.httpMethods.isEmpty() || httpMethod in it.httpMethods },
            "THIM-URL-METHOD",
            location,
        ) {
            val mapped = matching.flatMap { it.httpMethods.ifEmpty { setOf("any") } }.distinct().sorted()
            "$subject: '$plain' is not mapped for $httpMethod; mapped methods: ${mapped.joinToString(", ")}"
        }
    }

    /**
     * Fallback for applications that map one literal route per enum constant instead of
     * a parameterized route. The closed enum set proves coverage: a constant without a
     * matching route fails compilation.
     */
    private fun checkEnumExpansion(
        parts: List<PathPart>,
        enumVariables: Map<String, List<String>>,
        httpMethod: String,
        location: SourceLocation?,
        subject: String,
    ) {
        var combinations: List<Map<String, String>> = listOf(emptyMap())
        enumVariables.forEach { (variable, constants) ->
            combinations = combinations.flatMap { combination ->
                constants.map { constant -> combination + (variable to constant) }
            }
        }
        val missing = combinations.mapNotNull { combination ->
            val concrete = parts.map { part ->
                combination[variableName(part)]?.let { PathPart(it, variable = false) } ?: part
            }
            val served = routes.any {
                matches(it.segments, concrete) && (it.httpMethods.isEmpty() || httpMethod in it.httpMethods)
            }
            if (served) {
                null
            } else {
                val values = combination.entries.joinToString(", ") { "${it.key}=${it.value}" }
                "$values ('/${concrete.joinToString("/") { it.value }}')"
            }
        }
        requireDiagnostic(missing.isEmpty(), "THIM-URL-ENUM-ROUTE", location) {
            "$subject: no $httpMethod controller mapping for ${missing.joinToString("; ")}"
        }
    }

    private fun variableName(part: PathPart): String? =
        if (part.variable) part.value.removeSurrounding("{", "}") else null

    private fun known(): String {
        val patterns = routes.map { route ->
            val methods = route.httpMethods.sorted().joinToString(",").ifEmpty { "any" }
            "$methods ${route.pattern}"
        }.distinct().sorted()
        val shown = patterns.take(8).joinToString(", ")
        return if (patterns.size > 8) "$shown, …" else shown
    }

    /**
     * A trustedPaths entry is matched segment by segment: a plain path matches only
     * itself, '*' matches exactly one segment, and a final '**' matches the path and
     * any subtree below it. Malformed entries fail compilation instead of silently
     * never matching.
     */
    private fun trustedPattern(entry: String): List<RouteSegment> {
        val trimmed = entry.trim().trimEnd('/').ifEmpty { "/" }
        require(trimmed.startsWith('/')) {
            "thim.trustedPaths entry '$entry' must start with '/'"
        }
        val segments = trimmed.trim('/').split('/').filter(String::isNotEmpty)
        return segments.mapIndexed { index, segment ->
            when {
                segment == "**" -> {
                    require(index == segments.lastIndex) {
                        "thim.trustedPaths entry '$entry': '**' is only supported as the final segment"
                    }
                    RouteSegment.TailWildcard
                }
                segment == "*" -> RouteSegment.Variable
                '*' in segment -> throw IllegalArgumentException(
                    "thim.trustedPaths entry '$entry': wildcards must be a whole segment ('*' or a final '**')",
                )
                else -> RouteSegment.Literal(segment)
            }
        }
    }

    private data class PathPart(val value: String, val variable: Boolean)

    private fun pathParts(path: String): List<PathPart> =
        path.trim('/').split('/').filter(String::isNotEmpty).map { segment ->
            PathPart(segment, segment.startsWith('{') && segment.endsWith('}'))
        }

    private fun matches(route: List<RouteSegment>, parts: List<PathPart>): Boolean {
        route.forEachIndexed { index, segment ->
            if (segment is RouteSegment.TailWildcard) return true
            val part = parts.getOrNull(index) ?: return false
            when (segment) {
                is RouteSegment.Literal -> if (part.variable || part.value != segment.value) return false
                else -> {}
            }
        }
        return route.size == parts.size
    }

    companion object {
        private const val MAPPING_PACKAGE = "org.springframework.web.bind.annotation"
        private val mappingAnnotations = mapOf(
            "$MAPPING_PACKAGE.GetMapping" to setOf("GET"),
            "$MAPPING_PACKAGE.PostMapping" to setOf("POST"),
            "$MAPPING_PACKAGE.PutMapping" to setOf("PUT"),
            "$MAPPING_PACKAGE.PatchMapping" to setOf("PATCH"),
            "$MAPPING_PACKAGE.DeleteMapping" to setOf("DELETE"),
            "$MAPPING_PACKAGE.RequestMapping" to emptySet(),
        )

        fun load(resolver: Resolver, trustedPaths: List<String>): RouteCatalog {
            val routes = mutableListOf<Route>()
            val files = mutableListOf<KSFile>()
            mappingAnnotations.forEach { (annotationName, httpMethods) ->
                resolver.getSymbolsWithAnnotation(annotationName)
                    .filterIsInstance<KSFunctionDeclaration>()
                    .forEach { function ->
                        val annotation = function.annotations.first { it.matches(annotationName) }
                        val methods = httpMethods.ifEmpty { requestMethods(annotation) }
                        val prefixes = classPrefixes(function)
                        function.containingFile?.let(files::add)
                        paths(annotation).forEach { path ->
                            prefixes.forEach { prefix ->
                                val pattern = combine(prefix, path)
                                routes.add(Route(methods, pattern, segments(pattern)))
                            }
                        }
                    }
            }
            return RouteCatalog(routes, trustedPaths, files.distinct())
        }

        private fun KSAnnotation.matches(qualifiedName: String): Boolean =
            shortName.asString() == qualifiedName.substringAfterLast('.') &&
                annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName

        private fun classPrefixes(function: KSFunctionDeclaration): List<String> {
            val parent = function.parentDeclaration as? KSClassDeclaration ?: return listOf("")
            val mapping = parent.annotations.firstOrNull { it.matches("$MAPPING_PACKAGE.RequestMapping") }
                ?: return listOf("")
            return paths(mapping).ifEmpty { listOf("") }
        }

        private fun paths(annotation: KSAnnotation): List<String> {
            val values = annotation.arguments
                .filter { it.name?.asString() in setOf("value", "path") }
                .flatMap { argument ->
                    when (val value = argument.value) {
                        is String -> listOf(value)
                        is List<*> -> value.filterIsInstance<String>()
                        else -> emptyList()
                    }
                }
                .distinct()
            return values.ifEmpty { listOf("") }
        }

        private fun requestMethods(annotation: KSAnnotation): Set<String> {
            val argument = annotation.arguments.firstOrNull { it.name?.asString() == "method" } ?: return emptySet()
            val values = when (val value = argument.value) {
                is List<*> -> value
                null -> emptyList()
                else -> listOf(value)
            }
            return values.mapNotNull(::enumName).toSet()
        }

        private fun enumName(value: Any?): String? = when (value) {
            is KSType -> value.declaration.simpleName.asString()
            is KSDeclaration -> value.simpleName.asString()
            null -> null
            else -> value.toString().substringAfterLast('.')
        }.takeIf { !it.isNullOrBlank() }

        private fun combine(prefix: String, path: String): String {
            val joined = "/${prefix.trim('/')}/${path.trim('/')}".replace(Regex("/+"), "/")
            return if (joined.length > 1) joined.trimEnd('/') else joined
        }

        private fun segments(pattern: String): List<RouteSegment> =
            pattern.trim('/').split('/').filter(String::isNotEmpty).map { segment ->
                when {
                    segment == "**" || segment.startsWith("{*") -> RouteSegment.TailWildcard
                    segment == "*" -> RouteSegment.Variable
                    segment.startsWith('{') -> RouteSegment.Variable
                    else -> RouteSegment.Literal(segment)
                }
            }
    }
}
