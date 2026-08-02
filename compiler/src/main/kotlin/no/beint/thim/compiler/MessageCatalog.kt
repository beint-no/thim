package no.beint.thim.compiler

import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

internal data class MessageDefinition(
    val key: String,
    val base: String,
    val localized: Map<String, String>,
    val placeholders: Set<Int>,
)

internal class MessageCatalog private constructor(
    private val definitions: Map<String, MessageDefinition>,
) {
    private val referenced = linkedSetOf<String>()

    fun use(key: String, argumentCount: Int, context: String): MessageDefinition {
        val definition = definitions[key] ?: error("$context: message '$key' does not exist")
        val expected = if (definition.placeholders.isEmpty()) 0 else definition.placeholders.max() + 1
        require(argumentCount == expected) {
            "$context: message '$key' requires $expected arguments, received $argumentCount"
        }
        referenced.add(key)
        return definition
    }

    fun unused(): Set<String> = definitions.keys - referenced

    fun locales(): Set<String> = definitions.values
        .flatMapTo(linkedSetOf()) { definition ->
            definition.localized.filterValues { it != definition.base }.keys
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
                    val placeholders = placeholders(value, "$baseFile:$key")
                    val localizedValues = localized.mapValues { (locale, values) ->
                        val localizedValue = values.getValue(key)
                        require(placeholders(localizedValue, "$locale:$key") == placeholders) {
                            "Message '$key' uses different placeholders in locale '$locale'"
                        }
                        localizedValue
                    }
                    definitions[key] = MessageDefinition(key, value, localizedValues, placeholders)
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
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader: Reader -> properties.load(reader) }
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

        private val bundlePattern = Regex("(?:messages|[A-Za-z0-9_-]+-msgs)(?:_[a-z]{2}(?:_[A-Z]{2})?)?\\.properties")
        private val localeSuffix = Regex("_([a-z]{2}(?:_[A-Z]{2})?)$")
        private val placeholderPattern = Regex("\\{(\\d+)}")
        private const val fileLabel = "Message bundle"
    }
}
