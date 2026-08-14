package no.beint.thim.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

internal data class CssUsageHit(
    val name: String,
    val file: String,
)

internal class CssUsage(
    val tokens: Set<String>,
    val prefixes: Set<String>,
    val hits: List<CssUsageHit>,
) {
    companion object {
        private val usageExtensions = setOf("html", "kt", "java", "js")
        private val htmlClassAttribute = Regex(
            """(?:\bclass|th:class|th:classappend)\s*=\s*(["'])(.*?)\1""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        fun scan(roots: List<Path>): CssUsage {
            val tokens = linkedSetOf<String>()
            val prefixes = linkedSetOf<String>()
            val hits = mutableListOf<CssUsageHit>()
            roots.filter { Files.isDirectory(it) }.forEach { root ->
                Files.walk(root).use { paths ->
                    paths.filter { path -> path.isRegularFile() && path.extension.lowercase() in usageExtensions }
                        .filter { path -> !vendorPath(path, root) }
                        .sorted()
                        .forEach { path ->
                            val relative = path.relativeTo(root).pathString.replace('\\', '/')
                            val source = Files.readString(path, StandardCharsets.UTF_8)
                            collect(source, path.extension.lowercase(), relative, tokens, prefixes, hits)
                        }
                }
            }
            return CssUsage(tokens, prefixes, hits)
        }

        internal fun collect(
            source: String,
            extension: String,
            file: String,
            tokens: MutableSet<String>,
            prefixes: MutableSet<String>,
            hits: MutableList<CssUsageHit>,
        ) {
            if (extension == "html") {
                htmlClassAttribute.findAll(source).forEach { match ->
                    addLiteral(match.groupValues[2], file, tokens, prefixes, hits)
                }
            }
            extractStrings(source).forEach { literal ->
                addLiteral(literal, file, tokens, prefixes, hits)
            }
        }

        internal fun extractStrings(source: String): List<String> {
            val literals = mutableListOf<String>()
            var index = 0
            while (index < source.length) {
                val character = source[index]
                if (character != '"' && character != '\'') {
                    index++
                    continue
                }
                val quote = character
                val start = index + 1
                index = start
                while (index < source.length) {
                    when (source[index]) {
                        '\\' -> index = (index + 2).coerceAtMost(source.length)
                        quote -> break
                        else -> index++
                    }
                }
                if (index <= source.length) {
                    literals += source.substring(start, index.coerceAtMost(source.length))
                }
                if (index < source.length && source[index] == quote) {
                    index++
                }
            }
            return literals
        }

        private fun addLiteral(
            literal: String,
            file: String,
            tokens: MutableSet<String>,
            prefixes: MutableSet<String>,
            hits: MutableList<CssUsageHit>,
        ) {
            if (literal.isEmpty()) return
            interpolationSegments(literal).forEach { segment ->
                if (isDynamicPrefix(segment)) {
                    prefixes += segment
                    hits += CssUsageHit(segment, file)
                }
                classTokens(segment).forEach { token ->
                    tokens += token
                    hits += CssUsageHit(token, file)
                }
            }
        }

        internal fun interpolationSegments(literal: String): List<String> {
            val segments = mutableListOf<String>()
            var index = 0
            var start = 0
            while (index < literal.length) {
                if (literal[index] != '$') {
                    index++
                    continue
                }
                segments += literal.substring(start, index)
                index++
                if (index < literal.length && literal[index] == '{') {
                    var depth = 1
                    index++
                    while (index < literal.length && depth > 0) {
                        when (literal[index]) {
                            '{' -> depth++
                            '}' -> depth--
                        }
                        index++
                    }
                } else {
                    while (index < literal.length && (literal[index].isLetterOrDigit() || literal[index] == '_')) {
                        index++
                    }
                }
                start = index
            }
            segments += literal.substring(start)
            return segments.filter(String::isNotEmpty)
        }

        private fun classTokens(value: String): List<String> {
            val tokens = mutableListOf<String>()
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (!character.isLetter() && character != '_') {
                    index++
                    continue
                }
                val start = index
                index++
                while (index < value.length && isClassPart(value[index])) {
                    index++
                }
                tokens += value.substring(start, index)
            }
            return tokens
        }

        private fun isClassPart(character: Char): Boolean =
            character.isLetterOrDigit() || character == '-' || character == '_' ||
                character == ':' || character == '[' || character == ']' ||
                character == '(' || character == ')' || character == '%' ||
                character == '.' || character == '+' || character == '/' || character == ','

        internal fun isDynamicPrefix(prefix: String): Boolean =
            prefix.length >= 10 && prefix.endsWith('-') && prefix.count { it == '-' } >= 2

        private fun vendorPath(path: Path, root: Path): Boolean =
            path.relativeTo(root).pathString.replace('\\', '/').split('/').any { part ->
                part.equals("vendor", ignoreCase = true)
            }
    }
}
