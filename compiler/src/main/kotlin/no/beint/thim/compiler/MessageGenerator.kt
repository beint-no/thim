package no.beint.thim.compiler

import javax.lang.model.SourceVersion

internal class MessageGenerator(private val catalog: MessageCatalog) {
    fun generate(packageName: String, className: String): String {
        val definitions = catalog.exportedDefinitions().toSortedMap()
        val groups = definitions.entries.groupBy { it.key.substringBefore('.') }.toSortedMap()
        val groupNames = uniqueNames(groups.keys, setOf(className), ::className)

        return buildString {
            appendLine("package $packageName;")
            appendLine()
            appendLine("public final class $className {")
            appendLine("    private $className() {}")
            appendLine()
            appendLocaleResolver()
            groups.forEach { (namespace, entries) ->
                appendLine()
                appendLine("    public static final class ${groupNames.getValue(namespace)} {")
                appendLine("        private ${groupNames.getValue(namespace)}() {}")
                val methodNames = uniqueNames(entries.map { it.key }) { key ->
                    methodName(key.substringAfter('.', missingDelimiterValue = key))
                }
                entries.forEach { (key, definition) ->
                    appendLine()
                    appendMessageMethod(methodNames.getValue(key), definition)
                }
                appendLine("    }")
            }
            appendLine("}")
        }
    }

    private fun StringBuilder.appendLocaleResolver() {
        val localized = catalog.supportedLocales.filter { it != catalog.defaultLocale }
        val localeIds = localized.mapIndexed { index, locale -> locale to index + 1 }.toMap()
        val languages = localeIds.filterKeys { '-' !in it }
        val regional = localeIds.filterKeys { '-' in it }
        appendLine("    private static int messageLocale(java.util.Locale locale) {")
        appendLine("        java.util.Objects.requireNonNull(locale, \"locale\");")
        if (languages.isEmpty()) {
            appendLine("        var language = 0;")
        } else {
            appendLine("        var language = switch (locale.getLanguage()) {")
            languages.forEach { (locale, id) -> appendLine("            case \"${javaString(locale)}\" -> $id;") }
            appendLine("            default -> 0;")
            appendLine("        };")
        }
        if (regional.isEmpty()) {
            appendLine("        return language;")
        } else {
            appendLine("        return switch (locale.toLanguageTag()) {")
            regional.forEach { (locale, id) -> appendLine("            case \"${javaString(locale)}\" -> $id;") }
            appendLine("            default -> language;")
            appendLine("        };")
        }
        appendLine("    }")
    }

    private fun StringBuilder.appendMessageMethod(name: String, definition: MessageDefinition) {
        val parameterNames = parameterNames(definition.arguments.keys)
        val parameters = definition.arguments.entries.joinToString(", ") { (argument, kind) ->
            "${parameterType(kind)} ${parameterNames.getValue(argument)}"
        }
        appendLine("        public static no.beint.thim.CompiledMessage $name($parameters) {")
        definition.arguments.forEach { (argument, kind) ->
            if (kind != MessageArgumentKind.NUMBER) {
                val parameter = parameterNames.getValue(argument)
                appendLine("            java.util.Objects.requireNonNull($parameter, \"$parameter\");")
            }
        }
        appendLine("            return locale -> {")
        appendLine("                java.util.Objects.requireNonNull(locale, \"locale\");")
        appendLine("                var output = new java.lang.StringBuilder();")
        val defaultValue = definition.values.getValue(catalog.defaultLocale)
        val localized = definition.values.filterKeys { it != catalog.defaultLocale }
        if (localized.isEmpty()) {
            appendMessageValue(defaultValue, parameterNames, catalog.defaultLocale, "                ")
        } else {
            val localeIds = catalog.supportedLocales
                .filter { it != catalog.defaultLocale }
                .mapIndexed { index, locale -> locale to index + 1 }
                .toMap()
            appendLine("                switch (messageLocale(locale)) {")
            localized.forEach { (locale, value) ->
                appendLine("                    case ${localeIds.getValue(locale)} -> {")
                appendMessageValue(value, parameterNames, locale, "                        ")
                appendLine("                    }")
            }
            appendLine("                    default -> {")
            appendMessageValue(defaultValue, parameterNames, catalog.defaultLocale, "                        ")
            appendLine("                    }")
            appendLine("                }")
        }
        appendLine("                return output.toString();")
        appendLine("            };")
        appendLine("        }")
    }

    private fun StringBuilder.appendMessageValue(
        value: MessageValue,
        parameters: Map<String, String>,
        locale: String,
        indentation: String,
    ) {
        when (value) {
            is MessagePattern -> value.parts.forEach { part ->
                when (part) {
                    is MessageText -> appendLine("${indentation}output.append(\"${javaString(part.value)}\");")
                    is MessageArgument -> appendLine("${indentation}output.append(${parameters.getValue(part.name)});")
                }
            }

            is MessageSelection -> {
                val parameter = parameters.getValue(value.argument)
                val selector = when (value.kind) {
                    MessageArgumentKind.NUMBER -> {
                        val configured = java.util.Locale.forLanguageTag(locale)
                        "no.beint.thim.PluralRules.cardinal(\"${javaString(configured.language)}\", " +
                                "\"${javaString(configured.country)}\", $parameter)"
                    }
                    MessageArgumentKind.SELECT -> parameter
                    MessageArgumentKind.TEXT -> error("Text arguments cannot select message variants")
                }
                appendLine("${indentation}switch ($selector) {")
                value.variants.filterKeys { it != "other" }.forEach { (variant, selected) ->
                    appendLine("${indentation}    case \"${javaString(variant)}\" -> {")
                    appendMessageValue(selected, parameters, locale, "$indentation        ")
                    appendLine("${indentation}    }")
                }
                appendLine("${indentation}    default -> {")
                appendMessageValue(value.variants.getValue("other"), parameters, locale, "$indentation        ")
                appendLine("${indentation}    }")
                appendLine("$indentation}")
            }
        }
    }

    private fun parameterNames(arguments: Set<String>): Map<String, String> {
        val used = mutableSetOf("locale", "output")
        return arguments.associateWith { argument ->
            val base = javaIdentifier(argument, "argument")
            generateSequence(base) { "${it}_" }.first(used::add)
        }
    }

    private fun parameterType(kind: MessageArgumentKind): String = when (kind) {
        MessageArgumentKind.TEXT, MessageArgumentKind.SELECT -> "String"
        MessageArgumentKind.NUMBER -> "long"
    }

    private fun uniqueNames(
        values: Collection<String>,
        reserved: Set<String> = emptySet(),
        baseName: (String) -> String,
    ): Map<String, String> {
        val used = reserved.toMutableSet()
        return values.sorted().associateWith { value ->
            generateSequence(baseName(value)) { "${it}_" }.first(used::add)
        }
    }

    private fun className(namespace: String): String = words(namespace)
        .joinToString("") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
        .let { javaIdentifier(it, "Messages") }

    private fun methodName(key: String): String {
        val parts = words(key)
        val candidate = if (parts.isEmpty()) {
            "message"
        } else {
            parts.first().lowercase() + parts.drop(1).joinToString("") {
                it.lowercase().replaceFirstChar(Char::uppercaseChar)
            }
        }
        return javaIdentifier(candidate, "message")
    }

    private fun words(value: String): List<String> = value.split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)

    private fun javaIdentifier(value: String, fallback: String): String {
        val sanitized = value.map { if (it == '_' || it.isLetterOrDigit()) it else '_' }.joinToString("")
            .ifEmpty { fallback }
            .let { if (it.first().isJavaIdentifierStart()) it else "_$it" }
        return if (SourceVersion.isKeyword(sanitized)) "${sanitized}_" else sanitized
    }

    private fun javaString(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> if (character.code < 32) "\\u%04x".format(character.code) else character
                },
            )
        }
    }
}
