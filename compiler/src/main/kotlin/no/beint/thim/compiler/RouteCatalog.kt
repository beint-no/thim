package no.beint.thim.compiler

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
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
    private val externalPrefixes: List<String>,
) {
    fun isEmpty(): Boolean = routes.isEmpty()

    fun check(path: String, httpMethod: String, location: SourceLocation?, subject: String) {
        if (routes.isEmpty()) return
        val plain = path.substringBefore('?').substringBefore('#')
        if (!plain.startsWith('/')) return
        if (externalPrefixes.any { plain == it || plain.startsWith("$it/") }) return
        val parts = pathParts(plain)
        if (parts.lastOrNull()?.let { !it.variable && '.' in it.value } == true) return
        val matching = routes.filter { matches(it.segments, parts) }
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

    private fun known(): String {
        val patterns = routes.map { route ->
            val methods = route.httpMethods.sorted().joinToString(",").ifEmpty { "any" }
            "$methods ${route.pattern}"
        }.distinct().sorted()
        val shown = patterns.take(8).joinToString(", ")
        return if (patterns.size > 8) "$shown, …" else shown
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

        fun load(resolver: Resolver, externalPaths: List<String>): RouteCatalog {
            val routes = mutableListOf<Route>()
            mappingAnnotations.forEach { (annotationName, httpMethods) ->
                resolver.getSymbolsWithAnnotation(annotationName)
                    .filterIsInstance<KSFunctionDeclaration>()
                    .forEach { function ->
                        val annotation = function.annotations.first { it.matches(annotationName) }
                        val methods = httpMethods.ifEmpty { requestMethods(annotation) }
                        val prefixes = classPrefixes(function)
                        paths(annotation).forEach { path ->
                            prefixes.forEach { prefix ->
                                val pattern = combine(prefix, path)
                                routes.add(Route(methods, pattern, segments(pattern)))
                            }
                        }
                    }
            }
            return RouteCatalog(routes, externalPaths.map { it.trimEnd('/') }.filter(String::isNotEmpty))
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
