package no.beint.thim.compiler

internal data class PathSegment(val name: String, val safe: Boolean)

internal data class PathExpression(val segments: List<PathSegment>)

internal data class MessageExpression(val key: String, val arguments: List<PathExpression>)

internal sealed interface UrlArgument

internal data class UrlLiteral(val value: String) : UrlArgument

internal data class UrlProperty(val path: PathExpression) : UrlArgument

internal data class UrlParameter(val name: String, val value: UrlArgument)

internal data class UrlExpression(
    val path: String,
    val pathVariables: List<String>,
    val parameters: List<UrlParameter>,
) {
    val queryParameters: List<UrlParameter> get() = parameters.filter { it.name !in pathVariables }

    fun parameter(name: String): UrlParameter = parameters.first { it.name == name }
}

internal object Expressions {
    fun path(value: String, context: String): PathExpression {
        val trimmed = value.trim()
        require(trimmed.startsWith("\${") && trimmed.endsWith('}')) {
            "$context: expected one \${property} expression; move interpolation and logic to the page model"
        }
        val body = trimmed.substring(2, trimmed.length - 1).trim()
        require(body.isNotEmpty()) { "$context: empty expression" }

        val segments = mutableListOf<PathSegment>()
        var position = 0
        var safe = false
        while (position < body.length) {
            val start = position
            require(body[position] == '_' || body[position].isLetter()) { "$context: invalid expression '$value'" }
            position++
            while (position < body.length && (body[position] == '_' || body[position].isLetterOrDigit())) position++
            segments.add(PathSegment(body.substring(start, position), safe))
            if (position == body.length) break
            safe = when {
                body.startsWith("?.", position) -> {
                    position += 2
                    true
                }
                body[position] == '.' -> {
                    position++
                    false
                }
                else -> error("$context: only property paths are supported, found '$value'")
            }
        }
        return PathExpression(segments)
    }

    fun message(value: String, context: String): MessageExpression {
        val trimmed = value.trim()
        require(trimmed.startsWith("#{") && trimmed.endsWith('}')) { "$context: expected a #{...} message" }
        val body = trimmed.substring(2, trimmed.length - 1).trim()
        val opening = body.indexOf('(')
        if (opening == -1) {
            require(body.matches(keyPattern)) { "$context: invalid message key '$body'" }
            return MessageExpression(body, emptyList())
        }
        require(body.endsWith(')')) { "$context: invalid message expression '$value'" }
        val key = body.substring(0, opening).trim()
        require(key.matches(keyPattern)) { "$context: invalid message key '$key'" }
        val arguments = splitArguments(body.substring(opening + 1, body.length - 1), context)
            .map { path(it, context) }
        return MessageExpression(key, arguments)
    }

    fun url(value: String, context: String): UrlExpression {
        val trimmed = value.trim()
        require(trimmed.startsWith("@{") && trimmed.endsWith('}')) { "$context: expected a @{/...} URL" }
        val body = trimmed.substring(2, trimmed.length - 1).trim()
        val opening = body.indexOf('(')
        val path: String
        val parameters: List<UrlParameter>
        if (opening == -1) {
            path = body
            parameters = emptyList()
        } else {
            require(body.endsWith(')')) { "$context: invalid URL expression '$value'" }
            path = body.substring(0, opening).trim()
            parameters = splitArguments(body.substring(opening + 1, body.length - 1), context)
                .map { parameter(it, context) }
        }
        require((path.startsWith('/') || path.startsWith("https://")) && !path.contains("\${")) {
            "$context: only application-relative or HTTPS URLs are supported"
        }
        val withoutVariables = pathVariablePattern.replace(path, "")
        require('{' !in withoutVariables && '}' !in withoutVariables) { "$context: invalid URL path '$path'" }
        val variables = pathVariablePattern.findAll(path).map { it.groupValues[1] }.toList()
        require(variables.size == variables.distinct().size) { "$context: duplicate path variable in '$path'" }
        val names = parameters.map { it.name }
        require(names.size == names.distinct().size) { "$context: duplicate URL parameter" }
        variables.forEach { variable ->
            require(parameters.any { it.name == variable }) {
                "$context: path variable '{$variable}' needs a matching parameter"
            }
        }
        return UrlExpression(path, variables, parameters)
    }

    private fun parameter(value: String, context: String): UrlParameter {
        val equals = value.indexOf('=')
        require(equals > 0) { "$context: expected name=value URL parameters" }
        val name = value.substring(0, equals).trim()
        require(name.matches(keyPattern)) { "$context: invalid URL parameter name '$name'" }
        val argument = value.substring(equals + 1).trim()
        return when {
            argument.startsWith("\${") -> UrlParameter(name, UrlProperty(path(argument, context)))
            argument.length >= 2 && argument.first() == '\'' && argument.last() == '\'' ->
                UrlParameter(name, UrlLiteral(argument.substring(1, argument.length - 1)))
            argument.matches(numberPattern) -> UrlParameter(name, UrlLiteral(argument))
            else -> error("$context: URL parameter '$name' must be a \${property}, 'literal', or number")
        }
    }

    private fun splitArguments(value: String, context: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        value.forEachIndexed { index, character ->
            when (character) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) {
                    result.add(value.substring(start, index).trim())
                    start = index + 1
                }
            }
            require(depth >= 0) { "$context: invalid message arguments" }
        }
        require(depth == 0) { "$context: invalid message arguments" }
        result.add(value.substring(start).trim())
        return result
    }

    private val keyPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    private val numberPattern = Regex("-?\\d+")
    private val pathVariablePattern = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")
}
