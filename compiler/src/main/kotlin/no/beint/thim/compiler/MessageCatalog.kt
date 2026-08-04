package no.beint.thim.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

internal data class MessageDefinition(
    val base: String,
    val localized: Map<String, String>,
)

internal class MessageCatalog private constructor(
    private val definitions: Map<String, MessageDefinition>,
) {
    private val used = linkedSetOf<String>()

    fun use(key: String, argumentCount: Int, context: String): MessageDefinition {
        val definition = definitions[key] ?: error(
            "$context: message '$key' does not exist${suggestion(key, definitions.keys)}",
        )
        used += key
        val placeholders = placeholders(definition.base, "$context message '$key'")
        definition.localized.forEach { (locale, value) ->
            require(placeholders(value, "$context message '$key' locale '$locale'") == placeholders) {
                "$context: message '$key' uses different placeholders in locale '$locale'"
            }
        }
        val expected = if (placeholders.isEmpty()) 0 else placeholders.max() + 1
        require(argumentCount == expected) {
            "$context: message '$key' requires $expected arguments, received $argumentCount"
        }
        return definition
    }

    fun locales(): Set<String> = definitions.values
        .flatMapTo(linkedSetOf()) { definition ->
            definition.localized.filterValues { it != definition.base }.keys
        }

    fun requireAllUsed() {
        val unused = definitions.keys - used
        require(unused.isEmpty()) { "Unused messages: ${unused.sorted()}" }
    }

    companion object {
        fun load(directory: Path): MessageCatalog {
            if (!Files.exists(directory)) return MessageCatalog(emptyMap())
            val files = Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension == "properties" && bundlePattern.matches(it.fileName.toString()) }
                    .sorted()
                    .toList()
            }
            val grouped = files.groupBy { bundleIdentity(it) }
            val definitions = linkedMapOf<String, MessageDefinition>()

            grouped.forEach { (identity, bundleFiles) ->
                val baseFile = bundleFiles.singleOrNull { localeOf(it) == null }
                    ?: error("Message bundle '$identity' has localized files but no base file")
                val base = readProperties(baseFile)
                val localized = bundleFiles.filter { it != baseFile }.associate { file ->
                    localeOf(file)!! to readProperties(file)
                }
                localized.forEach { (locale, values) ->
                    val missing = base.keys - values.keys
                    val extra = values.keys - base.keys
                    require(missing.isEmpty()) { "$fileLabel: locale '$locale' is missing ${missing.sorted()}" }
                    require(extra.isEmpty()) { "$fileLabel: locale '$locale' has extra keys ${extra.sorted()}" }
                }

                base.forEach { (key, value) ->
                    require(key !in definitions) { "Duplicate message key '$key'" }
                    val localizedValues = localized.mapValues { (locale, values) ->
                        values.getValue(key)
                    }
                    definitions[key] = MessageDefinition(value, localizedValues)
                }
            }
            return MessageCatalog(definitions)
        }

        private fun readProperties(path: Path): Map<String, String> {
            val keys = Files.readAllLines(path, StandardCharsets.UTF_8)
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
                .map { it.substringBefore('=').substringBefore(':').trim() }
                .toList()
            val duplicate = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            require(duplicate.isEmpty()) { "$path contains duplicate keys ${duplicate.sorted()}" }

            val properties = Properties()
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use(properties::load)
            return properties.stringPropertyNames().associateWith(properties::getProperty)
        }

        private fun placeholders(value: String, context: String): Set<Int> {
            val found = placeholderPattern.findAll(value).map { it.groupValues[1].toInt() }.toSet()
            val withoutPlaceholders = value.replace(placeholderPattern, "")
            require('{' !in withoutPlaceholders && '}' !in withoutPlaceholders) {
                "$context: only numeric {0} placeholders are supported"
            }
            if (found.isNotEmpty()) {
                require(found == (0..found.max()).toSet()) { "$context: placeholders must be contiguous from {0}" }
            }
            return found
        }

        private fun bundleIdentity(path: Path): String {
            val stem = path.nameWithoutExtension.replace(localeSuffix, "")
            return "${path.parent}:$stem"
        }

        private fun localeOf(path: Path): String? {
            val match = localeSuffix.find(path.nameWithoutExtension) ?: return null
            return match.groupValues[1].replace('_', '-')
        }

        private fun suggestion(value: String, candidates: Collection<String>): String {
            val nearest = candidates.minByOrNull { distance(value, it) } ?: return ""
            return if (distance(value, nearest) <= 2) "; did you mean '$nearest'?" else ""
        }

        private fun distance(left: String, right: String): Int {
            var previous = IntArray(right.length + 1) { it }
            left.forEachIndexed { leftIndex, leftCharacter ->
                val current = IntArray(right.length + 1)
                current[0] = leftIndex + 1
                right.forEachIndexed { rightIndex, rightCharacter ->
                    current[rightIndex + 1] = minOf(
                        previous[rightIndex + 1] + 1,
                        current[rightIndex] + 1,
                        previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1,
                    )
                }
                previous = current
            }
            return previous[right.length]
        }

        private val bundlePattern = Regex("(?:messages|[A-Za-z0-9_-]+-msgs)(?:_[a-z]{2}(?:_[A-Z]{2})?)?\\.properties")
        private val localeSuffix = Regex("_([a-z]{2}(?:_[A-Z]{2})?)$")
        private val placeholderPattern = Regex("\\{(\\d+)}")
        private const val fileLabel = "Message bundle"
    }
}
