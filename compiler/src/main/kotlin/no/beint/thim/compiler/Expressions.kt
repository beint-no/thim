package no.beint.thim.compiler

internal data class PathSegment(val name: String, val safe: Boolean)

internal data class PathExpression(val segments: List<PathSegment>)

internal data class MessageExpression(val key: String, val arguments: List<PathExpression>)

internal object Expressions {
    fun path(value: String, context: String): PathExpression {
        val trimmed = value.trim()
        require(trimmed.startsWith("\${") && trimmed.endsWith('}')) { "$context: expected a \${...} expression" }
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
}
