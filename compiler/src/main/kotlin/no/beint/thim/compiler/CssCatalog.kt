package no.beint.thim.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

internal data class CssClassDefinition(
    val name: String,
    val file: String,
)

internal class CssCatalog(
    val classes: Map<String, List<CssClassDefinition>>,
) {
    val names: Set<String> get() = classes.keys

    companion object {
        fun load(directory: Path): CssCatalog {
            if (!Files.isDirectory(directory)) {
                return CssCatalog(emptyMap())
            }
            val definitions = linkedMapOf<String, MutableList<CssClassDefinition>>()
            Files.walk(directory).use { paths ->
                paths.filter { path -> path.isRegularFile() && path.extension.equals("css", ignoreCase = true) }
                    .filter { path -> !vendorPath(path, directory) }
                    .sorted()
                    .forEach { path ->
                        val relative = path.relativeTo(directory).pathString.replace('\\', '/')
                        classNames(Files.readString(path, StandardCharsets.UTF_8)).forEach { name ->
                            definitions.getOrPut(name, ::mutableListOf)
                                .add(CssClassDefinition(name, relative))
                        }
                    }
            }
            return CssCatalog(definitions)
        }

        internal fun classNames(css: String): Set<String> {
            val names = linkedSetOf<String>()
            val source = stripComments(css)
            var index = 0
            var quote: Char? = null
            while (index < source.length) {
                val character = source[index]
                when {
                    quote != null -> {
                        if (character == '\\' && index + 1 < source.length) {
                            index += 2
                            continue
                        }
                        if (character == quote) quote = null
                        index++
                    }
                    character == '"' || character == '\'' -> {
                        quote = character
                        index++
                    }
                    character == '.' && classStart(source, index + 1) -> {
                        val parsed = readIdentifier(source, index + 1)
                        names += parsed.first
                        index = parsed.second
                    }
                    else -> index++
                }
            }
            return names
        }

        private fun vendorPath(path: Path, root: Path): Boolean =
            path.relativeTo(root).pathString.replace('\\', '/').split('/').any { part ->
                part.equals("vendor", ignoreCase = true)
            }

        private fun stripComments(css: String): String {
            val output = StringBuilder(css.length)
            var index = 0
            while (index < css.length) {
                if (css.startsWith("/*", index)) {
                    val end = css.indexOf("*/", index + 2)
                    if (end == -1) break
                    output.append(' ')
                    index = end + 2
                } else {
                    output.append(css[index])
                    index++
                }
            }
            return output.toString()
        }

        private fun classStart(source: String, index: Int): Boolean {
            if (index >= source.length) return false
            val character = source[index]
            return character == '_' || character == '-' || character == '\\' || character.isLetter()
        }

        private fun readIdentifier(source: String, start: Int): Pair<String, Int> {
            val raw = StringBuilder()
            var index = start
            while (index < source.length) {
                val character = source[index]
                when {
                    character == '\\' && index + 1 < source.length -> {
                        raw.append('\\').append(source[index + 1])
                        index += 2
                    }
                    character == '_' || character == '-' || character.isLetterOrDigit() -> {
                        raw.append(character)
                        index++
                    }
                    else -> break
                }
            }
            return unescape(raw.toString()) to index
        }

        internal fun unescape(identifier: String): String {
            val output = StringBuilder(identifier.length)
            var index = 0
            while (index < identifier.length) {
                val character = identifier[index]
                if (character != '\\' || index + 1 >= identifier.length) {
                    output.append(character)
                    index++
                    continue
                }
                val next = identifier[index + 1]
                if (next.isHexDigit()) {
                    var hex = index + 1
                    while (hex < identifier.length && hex - (index + 1) < 6 && identifier[hex].isHexDigit()) {
                        hex++
                    }
                    val codePoint = identifier.substring(index + 1, hex).toInt(16)
                    output.appendCodePoint(codePoint)
                    index = if (hex < identifier.length && identifier[hex] == ' ') hex + 1 else hex
                } else {
                    output.append(next)
                    index += 2
                }
            }
            return output.toString()
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
