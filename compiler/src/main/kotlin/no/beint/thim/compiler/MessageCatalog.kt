package no.beint.thim.compiler

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.NodeEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeType
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.schema.FailsafeSchema
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.IllformedLocaleException
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

internal enum class MessageArgumentKind {
    TEXT,
    NUMBER,
    SELECT,
}

internal sealed interface MessagePart

internal data class MessageText(val value: String) : MessagePart

internal data class MessageArgument(val name: String) : MessagePart

internal sealed interface MessageValue {
    fun arguments(): Map<String, MessageArgumentKind>
}

internal data class MessagePattern(val parts: List<MessagePart>) : MessageValue {
    override fun arguments(): Map<String, MessageArgumentKind> = parts
        .filterIsInstance<MessageArgument>()
        .associate { it.name to MessageArgumentKind.TEXT }
}

internal data class MessageSelection(
    val argument: String,
    val kind: MessageArgumentKind,
    val variants: Map<String, MessageValue>,
) : MessageValue {
    override fun arguments(): Map<String, MessageArgumentKind> = buildMap {
        put(argument, kind)
        variants.values.forEach { value ->
            value.arguments().forEach { (name, valueKind) ->
                val existing = putIfAbsent(name, valueKind)
                if (existing != null) put(name, mergeArgumentKinds(name, existing, valueKind))
            }
        }
    }
}

internal data class MessageDefinition(
    val values: Map<String, MessageValue>,
    val arguments: Map<String, MessageArgumentKind>,
)

internal class MessageCatalog private constructor(
    private val definitions: Map<String, MessageDefinition>,
    val defaultLocale: String,
    val supportedLocales: List<String>,
) {
    private val used = linkedSetOf<String>()

    fun use(key: String, arguments: Set<String>, context: String): MessageDefinition {
        val definition = definitions[key] ?: error(
            "$context: message '$key' does not exist${suggestion(key, definitions.keys)}",
        )
        used += key
        val expected = definition.arguments.keys
        require(arguments == expected) {
            val missing = expected - arguments
            val extra = arguments - expected
            buildString {
                append("$context: message '$key' arguments do not match")
                if (missing.isNotEmpty()) append("; missing ${missing.sorted()}")
                if (extra.isNotEmpty()) append("; unknown ${extra.sorted()}")
            }
        }
        return definition
    }

    fun requireAllUsed() {
        val unused = definitions.keys - used
        require(unused.isEmpty()) { "Unused messages: ${unused.sorted()}" }
    }

    companion object {
        fun load(directory: Path, defaultLocale: String, supportedLocales: List<String>): MessageCatalog {
            val canonicalDefault = canonicalLocale(defaultLocale)
            val configuredLocales = supportedLocales.map(::canonicalLocale)
            require(configuredLocales.size == configuredLocales.distinct().size) {
                "Supported locales contain duplicates: $configuredLocales"
            }
            if (!Files.exists(directory)) {
                val locales = configuredLocales.ifEmpty { listOf(canonicalDefault) }
                require(canonicalDefault in locales) {
                    "Default locale '$canonicalDefault' is not in supported locales $locales"
                }
                return MessageCatalog(emptyMap(), canonicalDefault, locales)
            }
            require(Files.isDirectory(directory)) { "Message catalog directory does not exist: $directory" }

            val rootEntries = Files.list(directory).use { paths -> paths.sorted().toList() }
            require(rootEntries.isNotEmpty()) { "Message catalog directory contains no locale directories: $directory" }
            val misplaced = rootEntries.filterNot { Files.isDirectory(it) }
            require(misplaced.isEmpty()) {
                "Message catalogs must be stored inside a locale directory, found $misplaced"
            }
            val discovered = rootEntries.map { canonicalLocale(it.name) }
            val canonicalSupported = configuredLocales.ifEmpty {
                listOf(canonicalDefault) + (discovered - canonicalDefault).sorted()
            }
            require(canonicalSupported.size == canonicalSupported.distinct().size) {
                "Supported locales contain duplicates: $canonicalSupported"
            }
            require(canonicalDefault in canonicalSupported) {
                "Default locale '$canonicalDefault' is not in supported locales $canonicalSupported"
            }
            val unexpected = discovered - canonicalSupported.toSet()
            require(unexpected.isEmpty()) { "Message catalog has unsupported locale directories $unexpected" }

            val localeFiles = canonicalSupported.associateWithTo(linkedMapOf()) { locale ->
                val localeDirectory = directory.resolve(locale)
                require(localeDirectory.isDirectory()) { "Message catalog is missing locale directory '$locale'" }
                val files = Files.walk(localeDirectory).use { paths ->
                    paths.filter(Files::isRegularFile).sorted().toList()
                }
                require(files.isNotEmpty()) { "Message catalog locale '$locale' contains no files" }
                val invalidFiles = files.filter { it.extension != "yaml" }
                require(invalidFiles.isEmpty()) {
                    "Message catalogs must use the .yaml extension in lowercase exclusively, found $invalidFiles"
                }
                files
            }
            val defaultLayout = localeFiles.getValue(canonicalDefault)
                .map { directory.resolve(canonicalDefault).relativize(it).toString() }
                .toSet()
            localeFiles.forEach { (locale, files) ->
                val layout = files.map { directory.resolve(locale).relativize(it).toString() }.toSet()
                val missing = defaultLayout - layout
                val extra = layout - defaultLayout
                require(missing.isEmpty() && extra.isEmpty()) {
                    buildString {
                        append("Message catalog locale '$locale' must mirror the '$canonicalDefault' file layout")
                        if (missing.isNotEmpty()) append("; missing ${missing.sorted()}")
                        if (extra.isNotEmpty()) append("; extra ${extra.sorted()}")
                    }
                }
            }

            val localeDefinitions = linkedMapOf<String, Map<String, MessageValue>>()
            canonicalSupported.forEach { locale ->
                val localeDirectory = directory.resolve(locale)
                localeDefinitions[locale] = readLocale(localeDirectory, locale, localeFiles.getValue(locale))
            }

            val base = localeDefinitions.getValue(canonicalDefault)
            localeDefinitions.forEach { (locale, values) ->
                val missing = base.keys - values.keys
                val extra = values.keys - base.keys
                require(missing.isEmpty()) { "Message catalog locale '$locale' is missing ${missing.sorted()}" }
                require(extra.isEmpty()) { "Message catalog locale '$locale' has extra keys ${extra.sorted()}" }
            }

            val definitions = base.keys.associateWithTo(linkedMapOf()) { key ->
                val values = canonicalSupported.associateWithTo(linkedMapOf()) { locale ->
                    localeDefinitions.getValue(locale).getValue(key)
                }
                val arguments = values.getValue(canonicalDefault).arguments()
                values.forEach { (locale, value) ->
                    validatePluralCategories(key, locale, value)
                    val localizedArguments = value.arguments()
                    val missing = arguments.keys - localizedArguments.keys
                    val extra = localizedArguments.keys - arguments.keys
                    require(missing.isEmpty() && extra.isEmpty()) {
                        buildString {
                            append("Message '$key' in locale '$locale' changes the argument contract")
                            if (missing.isNotEmpty()) append("; missing ${missing.sorted()}")
                            if (extra.isNotEmpty()) append("; extra ${extra.sorted()}")
                        }
                    }
                    localizedArguments.forEach { (name, kind) ->
                        require(arguments.getValue(name) == kind) {
                            "Message '$key' argument '$name' is ${arguments.getValue(name).name.lowercase()} " +
                                "in '$canonicalDefault' but ${kind.name.lowercase()} in '$locale'"
                        }
                    }
                }
                MessageDefinition(values, arguments)
            }
            return MessageCatalog(definitions, canonicalDefault, canonicalSupported)
        }

        private fun validatePluralCategories(key: String, locale: String, value: MessageValue) {
            if (!value.usesPlural()) return
            val language = Locale.forLanguageTag(locale).language
            val categories = pluralCategories[language]
            require(categories != null) {
                "Message '$key' uses _plural in locale '$locale', whose cardinal rules are not supported yet"
            }
            value.pluralSelections().forEach { selection ->
                val unreachable = selection.variants.keys - categories
                require(unreachable.isEmpty()) {
                    "Message '$key' in locale '$locale' uses unreachable plural categories ${unreachable.sorted()}; " +
                        "supported categories are ${categories.sorted()}"
                }
            }
        }

        private fun readLocale(directory: Path, locale: String, files: List<Path>): Map<String, MessageValue> {
            val definitions = linkedMapOf<String, MessageValue>()
            files.forEach { file ->
                val relative = directory.relativize(file)
                val namespaceParts = relative.map { it.toString() }.toMutableList()
                namespaceParts[namespaceParts.lastIndex] = relative.fileName.nameWithoutExtension
                namespaceParts.forEach { part ->
                    require(part.matches(keyPartPattern)) { "$file: invalid namespace component '$part'" }
                }
                val namespace = namespaceParts.joinToString(".")
                val parsed = parseFile(file, namespace)
                require(parsed.isNotEmpty()) { "$file: message catalog file contains no messages" }
                parsed.forEach { (key, value) ->
                    require(definitions.putIfAbsent(key, value) == null) { "$file: duplicate message '$key'" }
                }
            }
            return definitions
        }

        private fun parseFile(path: Path, namespace: String): Map<String, MessageValue> {
            val settings = LoadSettings.builder()
                .setLabel(path.toString())
                .setSchema(FailsafeSchema())
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(0)
                .setCodePointLimit(MAX_CATALOG_CODE_POINTS)
                .build()
            val source = Files.readString(path, StandardCharsets.UTF_8)
            validateEvents(path, settings, source)
            val documents = Compose(settings).composeAllFromString(source).toList()
            require(documents.size == 1) { "$path: expected exactly one YAML document" }
            val root = documents.single()
            requireNode(root, NodeType.MAPPING, path, "catalog root must be a mapping")
            val definitions = linkedMapOf<String, MessageValue>()
            parseNamespace(path, root as MappingNode, namespace, definitions)
            return definitions
        }

        private fun validateEvents(path: Path, settings: LoadSettings, source: String) {
            Parse(settings).parseString(source).forEach { event ->
                val violation = when {
                    event is SequenceStartEvent -> "YAML sequences are not supported"
                    event is NodeEvent && event.anchor.isPresent -> "anchors and aliases are not supported"
                    event is ScalarEvent && event.tag.isPresent -> "YAML tags are not supported"
                    event is CollectionStartEvent && event.tag.isPresent -> "YAML tags are not supported"
                    else -> null
                }
                if (violation != null) throw IllegalArgumentException(problem(path, event, violation))
            }
        }

        private fun parseNamespace(
            path: Path,
            mapping: MappingNode,
            prefix: String,
            definitions: MutableMap<String, MessageValue>,
        ) {
            val entries = mappingEntries(path, mapping)
            require(entries.keys.none { it.startsWith('_') }) {
                problem(path, mapping, "reserved metadata is only valid inside a message definition")
            }
            entries.forEach { (name, node) ->
                require(name.matches(keyPartPattern)) { problem(path, node, "invalid message key '$name'") }
                val key = "$prefix.$name"
                val value = when (node.nodeType) {
                    NodeType.SCALAR -> pattern(path, node as ScalarNode)
                    NodeType.MAPPING -> {
                        val child = node as MappingNode
                        val childEntries = mappingEntries(path, child)
                        if (childEntries.keys.any { it.startsWith('_') }) {
                            selection(path, child, childEntries)
                        } else {
                            parseNamespace(path, child, key, definitions)
                            null
                        }
                    }
                    else -> error(problem(path, node, "message values must be strings or nested mappings"))
                }
                if (value != null) {
                    require(definitions.putIfAbsent(key, value) == null) { problem(path, node, "duplicate message '$key'") }
                }
            }
        }

        private fun selection(path: Path, mapping: MappingNode, entries: Map<String, Node>): MessageSelection {
            val metadata = entries.keys.filter { it.startsWith('_') }
            require(metadata.size == 1 && metadata.single() in setOf("_plural", "_select")) {
                problem(path, mapping, "message definition needs exactly one of _plural or _select")
            }
            val metadataName = metadata.single()
            val selectorNode = entries.getValue(metadataName)
            requireNode(selectorNode, NodeType.SCALAR, path, "$metadataName must name an argument")
            val argument = (selectorNode as ScalarNode).value
            require(argument.matches(argumentPattern)) { problem(path, selectorNode, "invalid argument '$argument'") }
            val variants = entries.filterKeys { !it.startsWith('_') }.mapValuesTo(linkedMapOf()) { (name, node) ->
                require(name.matches(variantPattern)) { problem(path, node, "invalid variant '$name'") }
                when (node.nodeType) {
                    NodeType.SCALAR -> pattern(path, node as ScalarNode)
                    NodeType.MAPPING -> {
                        val nested = node as MappingNode
                        val nestedEntries = mappingEntries(path, nested)
                        require(nestedEntries.keys.any { it.startsWith('_') }) {
                            problem(path, nested, "variant mappings must define _plural or _select")
                        }
                        selection(path, nested, nestedEntries)
                    }
                    else -> error(problem(path, node, "variants must be strings or nested selections"))
                }
            }
            require("other" in variants) { problem(path, mapping, "message selection needs an 'other' variant") }
            val kind = if (metadataName == "_plural") MessageArgumentKind.NUMBER else MessageArgumentKind.SELECT
            if (kind == MessageArgumentKind.NUMBER) {
                val invalid = variants.keys - pluralVariants
                require(invalid.isEmpty()) { problem(path, mapping, "invalid plural variants ${invalid.sorted()}") }
            }
            return MessageSelection(argument, kind, variants)
        }

        private fun pattern(path: Path, scalar: ScalarNode): MessagePattern {
            requireSafeNode(path, scalar)
            val parts = mutableListOf<MessagePart>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotEmpty()) {
                    parts += MessageText(text.toString())
                    text.clear()
                }
            }
            val value = scalar.value
            var index = 0
            while (index < value.length) {
                when {
                    value.startsWith("{{", index) -> {
                        text.append('{')
                        index += 2
                    }
                    value.startsWith("}}", index) -> {
                        text.append('}')
                        index += 2
                    }
                    value[index] == '{' -> {
                        val end = value.indexOf('}', index + 1)
                        require(end >= 0) { problem(path, scalar, "unterminated message argument") }
                        val name = value.substring(index + 1, end)
                        require(name.matches(argumentPattern)) { problem(path, scalar, "invalid message argument '{$name}'") }
                        flush()
                        parts += MessageArgument(name)
                        index = end + 1
                    }
                    value[index] == '}' -> error(problem(path, scalar, "unmatched '}' in message; write '}}' for a literal brace"))
                    else -> text.append(value[index++])
                }
            }
            flush()
            return MessagePattern(parts)
        }

        private fun mappingEntries(path: Path, mapping: MappingNode): Map<String, Node> {
            requireSafeNode(path, mapping)
            val entries = linkedMapOf<String, Node>()
            mapping.value.forEach { tuple ->
                val keyNode = tuple.keyNode
                requireNode(keyNode, NodeType.SCALAR, path, "mapping keys must be strings")
                requireSafeNode(path, keyNode)
                val key = (keyNode as ScalarNode).value
                require(entries.putIfAbsent(key, tuple.valueNode) == null) {
                    problem(path, keyNode, "duplicate key '$key'")
                }
            }
            return entries
        }

        private fun requireNode(node: Node, type: NodeType, path: Path, message: String) {
            require(node.nodeType == type) { problem(path, node, message) }
            requireSafeNode(path, node)
        }

        private fun requireSafeNode(path: Path, node: Node) {
            require(node.anchor.isEmpty) { problem(path, node, "anchors and aliases are not supported") }
            val expectedTag = when (node.nodeType) {
                NodeType.SCALAR -> Tag.STR
                NodeType.MAPPING -> Tag.MAP
                else -> null
            }
            require(expectedTag == null || node.tag == expectedTag) { problem(path, node, "YAML tags are not supported") }
            if (node is ScalarNode) {
                require(node.value.codePoints().noneMatch { it in 0xD800..0xDFFF }) {
                    problem(path, node, "invalid Unicode scalar value: unpaired surrogate")
                }
            }
        }

        private fun problem(path: Path, node: Node, message: String): String =
            node.startMark.map { mark -> "$path:${mark.line + 1}:${mark.column + 1}: $message" }
                .orElse("$path: $message")

        private fun problem(path: Path, event: Event, message: String): String =
            event.startMark.map { mark -> "$path:${mark.line + 1}:${mark.column + 1}: $message" }
                .orElse("$path: $message")

        private fun canonicalLocale(value: String): String {
            val locale = try {
                Locale.Builder().setLanguageTag(value).build()
            } catch (exception: IllformedLocaleException) {
                throw IllegalArgumentException("Invalid locale '$value': ${exception.message}", exception)
            }
            val canonical = locale.toLanguageTag()
            require(canonical != "und" && canonical == value) {
                "Locale '$value' is not a canonical BCP 47 tag; use '$canonical'"
            }
            return canonical
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

        private val keyPartPattern = Regex("[A-Za-z][A-Za-z0-9_-]*")
        private val argumentPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val variantPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
        private val pluralVariants = setOf("zero", "one", "two", "few", "many", "other")
        private val pluralCategories = buildMap {
            listOf(
                "af", "bg", "da", "de", "el", "en", "eo", "et", "eu", "fi", "fo", "hu",
                "is", "nb", "nl", "nn", "no", "sq", "sv", "sw",
            ).forEach { put(it, setOf("one", "other")) }
            listOf("fr", "pt", "es", "ca", "gl", "it").forEach { put(it, setOf("one", "many", "other")) }
            listOf("cs", "sk", "bs", "hr", "sr", "lt", "ro").forEach {
                put(it, setOf("one", "few", "other"))
            }
            put("pl", setOf("one", "few", "many", "other"))
            put("sl", setOf("one", "two", "few", "other"))
            put("lv", setOf("zero", "one", "other"))
            put("ga", setOf("one", "two", "few", "many", "other"))
            put("cy", pluralVariants)
            put("gd", setOf("one", "two", "few", "other"))
        }
        private const val MAX_CATALOG_CODE_POINTS = 3 * 1024 * 1024
    }
}

internal fun MessageValue.usesPlural(): Boolean = when (this) {
    is MessagePattern -> false
    is MessageSelection -> kind == MessageArgumentKind.NUMBER || variants.values.any(MessageValue::usesPlural)
}

private fun MessageValue.pluralSelections(): List<MessageSelection> = when (this) {
    is MessagePattern -> emptyList()
    is MessageSelection -> (if (kind == MessageArgumentKind.NUMBER) listOf(this) else emptyList()) +
        variants.values.flatMap(MessageValue::pluralSelections)
}

internal fun MessageValue.selectVariants(argument: String): Set<String> = when (this) {
    is MessagePattern -> emptySet()
    is MessageSelection -> buildSet {
        if (kind == MessageArgumentKind.SELECT && this@selectVariants.argument == argument) {
            addAll(variants.keys - "other")
        }
        variants.values.forEach { addAll(it.selectVariants(argument)) }
    }
}

private fun mergeArgumentKinds(name: String, left: MessageArgumentKind, right: MessageArgumentKind): MessageArgumentKind = when {
    left == right -> left
    left == MessageArgumentKind.TEXT -> right
    right == MessageArgumentKind.TEXT -> left
    else -> error("Message argument '$name' is used as both ${left.name.lowercase()} and ${right.name.lowercase()}")
}
