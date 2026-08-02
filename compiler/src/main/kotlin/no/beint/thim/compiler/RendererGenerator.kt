package no.beint.thim.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal data class CompiledTemplate(
    val model: KSClassDeclaration,
    val rendererName: String,
    val source: String,
)

internal class StaticContent {
    private val output = ByteArrayOutputStream()

    fun append(value: String): IntRange {
        val start = output.size()
        output.writeBytes(value.toByteArray(StandardCharsets.UTF_8))
        return start until output.size()
    }

    fun bytes(): ByteArray = output.toByteArray()
}

internal class RendererGenerator(
    private val catalog: MessageCatalog,
    private val staticContent: StaticContent,
    private val registryName: String,
) {
    private var generatedVariable = 0
    private var regionalLocales = emptyMap<String, Int>()
    private var languages = emptyMap<String, Int>()

    fun compile(templateName: String, model: KSClassDeclaration, nodes: List<Node>): CompiledTemplate {
        val modelName = model.qualifiedName?.asString() ?: error("$templateName: model must have a qualified name")
        val rendererName = modelName.replace(Regex("[^A-Za-z0-9_]"), "_") + "ThimRenderer"
        val code = CodeWriter(staticContent, registryName)
        val locales = if (usesMessages(nodes)) catalog.locales() else emptySet()
        regionalLocales = locales.filter { '-' in it }.withIndex().associate { (index, locale) -> locale to index + 1 }
        languages = locales.filter { '-' !in it }.withIndex().associate { (index, locale) -> locale to index + 1 }

        code.line("final class $rendererName {")
        code.indent {
            code.line("private $rendererName() {}")
            code.line()
            code.line("static void render($modelName model, RenderContext context, HtmlOutput output) throws IOException {")
            code.indent {
                if (regionalLocales.isNotEmpty()) {
                    val cases = regionalLocales.entries.joinToString(" ") { (locale, index) ->
                        "case \"${javaString(locale)}\" -> $index;"
                    }
                    code.statement("var locale = switch (context.locale().toLanguageTag()) { $cases default -> 0; };")
                }
                if (languages.isNotEmpty()) {
                    val cases = languages.entries.joinToString(" ") { (locale, index) ->
                        "case \"${javaString(locale)}\" -> $index;"
                    }
                    code.statement("var language = switch (context.locale().getLanguage()) { $cases default -> 0; };")
                }
                val scope = Scope(model)
                nodes.forEach { renderNode(it, scope, code, templateName) }
            }
            code.line("}")
        }
        code.line("}")
        return CompiledTemplate(model, rendererName, code.toString())
    }

    private fun renderNode(node: Node, scope: Scope, code: CodeWriter, context: String) {
        when (node) {
            is RawNode -> code.static(node.value)
            is ElementNode -> renderElement(node, scope, code, context)
        }
    }

    private fun renderElement(element: ElementNode, parentScope: Scope, code: CodeWriter, context: String) {
        val location = "$context:<${element.name}>"
        val attributes = element.attributes
        val unsupported = attributes.keys.filter { it in unsupportedAttributes }
        require(unsupported.isEmpty()) { "$location: unsupported attributes $unsupported" }
        require(!(attributes.containsKey("th:if") && attributes.containsKey("th:unless"))) {
            "$location: th:if and th:unless cannot be combined"
        }

        var scope = parentScope
        var blocks = 0
        attributes["th:each"]?.let { each ->
            val match = eachPattern.matchEntire(each.trim()) ?: error("$location: expected 'item : \${items}'")
            val variable = match.groupValues[1]
            val collection = scope.resolve(Expressions.path(match.groupValues[2], "$location th:each"), "$location th:each")
            require(!collection.nullable) { "$location: th:each collection cannot be nullable" }
            val elementType = iterableElement(collection.type)
                ?: error("$location: '${match.groupValues[2]}' is not iterable")
            val generatedName = "item${generatedVariable++}"
            code.open("for (var $generatedName : ${collection.code})")
            scope = scope.withBinding(variable, Binding(generatedName, elementType, elementType.nullability == Nullability.NULLABLE))
            blocks++
        }

        attributes["th:if"]?.let { condition ->
            val resolved = scope.resolve(Expressions.path(condition, "$location th:if"), "$location th:if")
            require(resolved.type.declaration.qualifiedName?.asString() == "kotlin.Boolean" && !resolved.nullable) {
                "$location: th:if requires a non-null Boolean"
            }
            code.open("if (${resolved.code})")
            blocks++
        }
        attributes["th:unless"]?.let { condition ->
            val resolved = scope.resolve(Expressions.path(condition, "$location th:unless"), "$location th:unless")
            require(resolved.type.declaration.qualifiedName?.asString() == "kotlin.Boolean" && !resolved.nullable) {
                "$location: th:unless requires a non-null Boolean"
            }
            code.open("if (!${resolved.code})")
            blocks++
        }

        val transparent = element.name == "th:block"
        if (!transparent) {
            code.static("<${element.name}")
            element.attributes.forEach { (name, value) ->
                if (name.startsWith("th:") || name == "xmlns:th") return@forEach
                if ("th:$name" in element.attributes) return@forEach
                code.static(" $name")
                if (value != null) code.static("=\"$value\"")
            }
            element.attributes.forEach { (name, expression) ->
                if (name.startsWith("th:") && name !in controlAttributes) {
                    renderAttribute(name.removePrefix("th:"), requireNotNull(expression), scope, code, location)
                }
            }
            code.static(">")
        }

        val text = attributes["th:text"]
        if (text == null) {
            element.children.forEach { renderNode(it, scope, code, context) }
        } else if (text.trim().startsWith("#{")) {
            renderMessage(Expressions.message(text, "$location th:text"), scope, code, "$location th:text")
        } else {
            val value = scope.resolve(Expressions.path(text, "$location th:text"), "$location th:text")
            code.statement("output.text(${value.code});")
        }

        if (!transparent && element.name !in voidElements) code.static("</${element.name}>")
        repeat(blocks) { code.close() }
    }

    private fun renderAttribute(
        name: String,
        expression: String,
        scope: Scope,
        code: CodeWriter,
        context: String,
    ) {
        val location = "$context th:$name"
        if (name in booleanAttributes) {
            val value = scope.resolve(Expressions.path(expression, location), location)
            require(value.type.declaration.qualifiedName?.asString() == "kotlin.Boolean" && !value.nullable) {
                "$location requires a non-null Boolean"
            }
            code.open("if (${value.code})")
            code.static(" $name")
            code.close()
            return
        }

        when {
            expression.trim().startsWith("@{") -> {
                val path = parseUrl(expression, location)
                code.static(" $name=\"")
                if (path.startsWith('/')) code.statement("output.text(context.contextPath());")
                code.static(escapeHtml(path) + "\"")
            }
            expression.trim().startsWith("#{") -> {
                code.static(" $name=\"")
                renderMessage(Expressions.message(expression, location), scope, code, location)
                code.static("\"")
            }
            expression.trim().startsWith("\${") -> {
                if (expression.trim() == "\${#locale.language}") {
                    code.static(" $name=\"")
                    code.statement("output.text(context.locale().getLanguage());")
                    code.static("\"")
                    return
                }
                val value = scope.resolve(Expressions.path(expression, location), location)
                if (value.nullable) {
                    val variable = "attribute${generatedVariable++}"
                    code.statement("var $variable = ${value.code};")
                    code.open("if ($variable != null)")
                    code.static(" $name=\"")
                    code.statement("output.text($variable);")
                    code.static("\"")
                    code.close()
                } else {
                    code.static(" $name=\"")
                    code.statement("output.text(${value.code});")
                    code.static("\"")
                }
            }
            expression.length >= 2 && expression.first() == '\'' && expression.last() == '\'' ->
                code.static(" $name=\"${escapeHtml(expression.substring(1, expression.length - 1))}\"")
            else -> error("$location: expected a property, message, URL, or quoted literal")
        }
    }

    private fun renderMessage(expression: MessageExpression, scope: Scope, code: CodeWriter, context: String) {
        val definition = catalog.use(expression.key, expression.arguments.size, context)
        val arguments = expression.arguments.map { scope.resolve(it, context).code }
        val localized = definition.localized.filterValues { it != definition.base }
        val regional = localized.filterKeys { '-' in it }
        val languageValues = localized.filterKeys { '-' !in it }

        if (regional.isNotEmpty()) {
            code.open("switch (locale)")
            regional.forEach { (locale, value) ->
                code.open("case ${regionalLocales.getValue(locale)} ->")
                appendMessage(value, arguments, code)
                code.close()
            }
            code.open("default ->")
        }
        if (languageValues.isNotEmpty()) {
            code.open("switch (language)")
            languageValues.forEach { (locale, value) ->
                code.open("case ${languages.getValue(locale)} ->")
                appendMessage(value, arguments, code)
                code.close()
            }
            code.open("default ->")
            appendMessage(definition.base, arguments, code)
            code.close()
            code.close()
        } else {
            appendMessage(definition.base, arguments, code)
        }
        if (regional.isNotEmpty()) {
            code.close()
            code.close()
        }
    }

    private fun usesMessages(nodes: List<Node>): Boolean = nodes.any { node ->
        node is ElementNode && (
            node.attributes.values.any { it?.trim()?.startsWith("#{") == true } || usesMessages(node.children)
        )
    }

    private fun appendMessage(pattern: String, arguments: List<String>, code: CodeWriter) {
        var start = 0
        placeholderPattern.findAll(pattern).forEach { match ->
            code.static(escapeHtml(pattern.substring(start, match.range.first)))
            code.statement("output.text(${arguments[match.groupValues[1].toInt()]});")
            start = match.range.last + 1
        }
        code.static(escapeHtml(pattern.substring(start)))
    }

    private fun parseUrl(value: String, context: String): String {
        val trimmed = value.trim()
        require(trimmed.startsWith("@{") && trimmed.endsWith('}')) { "$context: expected a @{/...} URL" }
        val path = trimmed.substring(2, trimmed.length - 1)
        require((path.startsWith('/') || path.startsWith("https://")) && !path.contains("\${") && !path.contains('(')) {
            "$context: only static absolute application or HTTPS URLs are supported"
        }
        return path
    }

    private fun iterableElement(type: KSType): KSType? {
        val candidates = sequenceOf(type) + (type.declaration as? KSClassDeclaration).orEmptySuperTypes()
        return candidates.firstOrNull {
            it.declaration.qualifiedName?.asString() in setOf(
                "kotlin.collections.Iterable", "kotlin.collections.List", "kotlin.collections.Set", "kotlin.Array",
            )
        }?.arguments?.firstOrNull()?.type?.resolve()
    }

    private fun KSClassDeclaration?.orEmptySuperTypes(): Sequence<KSType> = this?.getAllSuperTypes() ?: emptySequence()

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private data class Binding(val code: String, val type: KSType, val nullable: Boolean)

    private data class ResolvedPath(val code: String, val type: KSType, val nullable: Boolean)

    private class Scope(
        private val model: KSClassDeclaration,
        private val bindings: Map<String, Binding> = emptyMap(),
    ) {
        fun withBinding(name: String, binding: Binding) = Scope(model, bindings + (name to binding))

        fun resolve(expression: PathExpression, context: String): ResolvedPath {
            val first = expression.segments.first()
            val bound = bindings[first.name]
            var code: String
            var type: KSType
            var nullable: Boolean
            if (bound != null) {
                code = bound.code
                type = bound.type
                nullable = bound.nullable
            } else {
                val property = model.getAllProperties().firstOrNull { it.simpleName.asString() == first.name }
                    ?: error("$context: '${first.name}' is not a property of ${model.qualifiedName?.asString()}")
                type = property.type.resolve()
                nullable = type.nullability == Nullability.NULLABLE
                code = "model.${getter(first.name, type)}()"
            }

            expression.segments.drop(1).forEach { segment ->
                require(!nullable || segment.safe) {
                    "$context: '${segment.name}' dereferences a nullable value; use ?."
                }
                val declaration = type.declaration as? KSClassDeclaration
                    ?: error("$context: ${type.declaration.qualifiedName?.asString()} has no properties")
                val property = declaration.getAllProperties().firstOrNull { it.simpleName.asString() == segment.name }
                    ?: error("$context: '${segment.name}' is not a property of ${declaration.qualifiedName?.asString()}")
                val nextType = property.type.resolve()
                val access = "$code.${getter(segment.name, nextType)}()"
                code = if (segment.safe) "($code == null ? null : $access)" else access
                type = nextType
                nullable = segment.safe || type.nullability == Nullability.NULLABLE
            }
            return ResolvedPath(code, type, nullable)
        }

        private fun getter(name: String, type: KSType): String {
            val boolean = type.declaration.qualifiedName?.asString() == "kotlin.Boolean"
            return if (boolean && name.startsWith("is") && name.getOrNull(2)?.isUpperCase() == true) {
                name
            } else {
                "get" + name.replaceFirstChar(Char::uppercaseChar)
            }
        }
    }

    private class CodeWriter(
        private val staticContent: StaticContent,
        private val registryName: String,
    ) {
        private val output = StringBuilder()
        private val pending = StringBuilder()
        private var depth = 0

        fun static(value: String) {
            pending.append(value)
        }

        fun statement(value: String) {
            flushStatic()
            line(value)
        }

        fun line(value: String = "") {
            flushStatic()
            output.append("    ".repeat(depth)).append(value).append('\n')
        }

        fun open(header: String) {
            line("$header {")
            depth++
        }

        fun close() {
            flushStatic()
            depth--
            line("}")
        }

        fun indent(block: () -> Unit) {
            depth++
            block()
            flushStatic()
            depth--
        }

        private fun flushStatic() {
            if (pending.isEmpty()) return
            val range = staticContent.append(pending.toString())
            output.append("    ".repeat(depth))
                .append("output.raw($registryName.STATIC, ")
                .append(range.first)
                .append(", ")
                .append(range.last - range.first + 1)
                .append(");\n")
            pending.clear()
        }

        override fun toString(): String {
            flushStatic()
            return output.toString()
        }
    }

    private companion object {
        val eachPattern = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(\\$\\{.+})")
        val placeholderPattern = Regex("\\{(\\d+)}")
        val voidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
        val booleanAttributes = setOf("checked", "disabled", "multiple", "readonly", "required", "selected")
        val controlAttributes = setOf("th:text", "th:each", "th:if", "th:unless", "th:fragment")
        val unsupportedAttributes = setOf(
            "th:attr", "th:case", "th:classappend", "th:errors", "th:field", "th:inline", "th:insert",
            "th:object", "th:remove", "th:replace", "th:switch", "th:utext", "th:with",
        )

        fun javaString(value: String): String = buildString(value.length) {
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
}
