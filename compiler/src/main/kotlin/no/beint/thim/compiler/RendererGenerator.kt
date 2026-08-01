package no.beint.thim.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability

internal data class CompiledTemplate(
    val model: KSClassDeclaration,
    val rendererName: String,
    val source: String,
)

internal class RendererGenerator(
    private val generatedPackage: String,
    private val catalog: MessageCatalog,
) {
    private var generatedVariable = 0

    fun compile(templateName: String, model: KSClassDeclaration, nodes: List<Node>): CompiledTemplate {
        val modelName = model.qualifiedName?.asString() ?: error("$templateName: model must have a qualified name")
        val rendererName = model.simpleName.asString().replace(Regex("[^A-Za-z0-9_]"), "_") + "ThimRenderer"
        val code = CodeWriter()
        val scope = Scope(model)

        code.line("private object $rendererName {")
        code.indent {
            code.line("fun render(model: $modelName, context: RenderContext, output: Appendable) {")
            code.indent {
                nodes.forEach { renderNode(it, scope, code, templateName) }
                code.flushStatic()
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
        val supported = setOf(
            "th:text", "th:each", "th:if", "th:unless", "th:href", "th:src", "th:action",
            "th:value", "th:checked", "th:selected", "th:disabled",
        )
        val unknown = attributes.keys.filter { it.startsWith("th:") && it !in supported }
        require(unknown.isEmpty()) { "$location: unsupported attributes $unknown" }
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
            val generatedName = "_item${generatedVariable++}"
            code.open("for ($generatedName in ${collection.code})")
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

        code.static("<${element.name}")
        element.attributes.forEach { (name, value) ->
            if (name.startsWith("th:") || name == "xmlns:th") return@forEach
            if ("th:$name" in element.attributes) return@forEach
            code.static(" $name")
            if (value != null) code.static("=\"$value\"")
        }

        listOf("href", "src", "action").forEach { name ->
            attributes["th:$name"]?.let { expression ->
                val path = parseUrl(expression, "$location th:$name")
                code.static(" $name=\"")
                code.dynamic("Html.text(output, context.contextPath)")
                code.static(escapeHtml(path) + "\"")
            }
        }
        attributes["th:value"]?.let { expression ->
            val value = scope.resolve(Expressions.path(expression, "$location th:value"), "$location th:value")
            code.static(" value=\"")
            code.dynamic("Html.text(output, ${value.code})")
            code.static("\"")
        }
        listOf("checked", "selected", "disabled").forEach { name ->
            attributes["th:$name"]?.let { expression ->
                val value = scope.resolve(Expressions.path(expression, "$location th:$name"), "$location th:$name")
                require(value.type.declaration.qualifiedName?.asString() == "kotlin.Boolean" && !value.nullable) {
                    "$location: th:$name requires a non-null Boolean"
                }
                code.open("if (${value.code})")
                code.static(" $name")
                code.close()
            }
        }

        code.static(">")
        val text = attributes["th:text"]
        if (text == null) {
            element.children.forEach { renderNode(it, scope, code, context) }
        } else if (text.trim().startsWith("#{")) {
            renderMessage(Expressions.message(text, "$location th:text"), scope, code, "$location th:text")
        } else {
            val value = scope.resolve(Expressions.path(text, "$location th:text"), "$location th:text")
            code.dynamic("Html.text(output, ${value.code})")
        }

        if (element.name !in voidElements) code.static("</${element.name}>")
        repeat(blocks) { code.close() }
    }

    private fun renderMessage(expression: MessageExpression, scope: Scope, code: CodeWriter, context: String) {
        val definition = catalog.use(expression.key, expression.arguments.size, context)
        val arguments = expression.arguments.map { scope.resolve(it, context).code }
        val regional = definition.localized.filterKeys { '-' in it }
        val languages = definition.localized.filterKeys { '-' !in it }

        if (regional.isNotEmpty()) {
            code.open("when (context.locale.toLanguageTag())")
            regional.forEach { (locale, value) ->
                code.open("\"${kotlinString(locale)}\" ->")
                appendMessage(value, arguments, code)
                code.close()
            }
            code.open("else ->")
        }
        if (languages.isNotEmpty()) {
            code.open("when (context.locale.language)")
            languages.forEach { (locale, value) ->
                code.open("\"${kotlinString(locale)}\" ->")
                appendMessage(value, arguments, code)
                code.close()
            }
            code.open("else ->")
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

    private fun appendMessage(pattern: String, arguments: List<String>, code: CodeWriter) {
        var start = 0
        placeholderPattern.findAll(pattern).forEach { match ->
            code.static(escapeHtml(pattern.substring(start, match.range.first)))
            code.dynamic("Html.text(output, ${arguments[match.groupValues[1].toInt()]})")
            start = match.range.last + 1
        }
        code.static(escapeHtml(pattern.substring(start)))
    }

    private fun parseUrl(value: String, context: String): String {
        val trimmed = value.trim()
        require(trimmed.startsWith("@{") && trimmed.endsWith('}')) { "$context: expected a @{/...} URL" }
        val path = trimmed.substring(2, trimmed.length - 1)
        require(path.startsWith('/') && !path.contains("\${") && !path.contains('(')) {
            "$context: only static absolute application paths are supported"
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
            var start: Int
            if (bound != null) {
                code = bound.code
                type = bound.type
                nullable = bound.nullable
                start = 1
            } else {
                val property = model.getAllProperties().firstOrNull { it.simpleName.asString() == first.name }
                    ?: error("$context: '${first.name}' is not a property of ${model.qualifiedName?.asString()}")
                type = property.type.resolve()
                nullable = type.nullability == Nullability.NULLABLE
                code = "model.${identifier(first.name)}"
                start = 1
            }

            expression.segments.drop(start).forEach { segment ->
                require(!nullable || segment.safe) {
                    "$context: '${segment.name}' dereferences a nullable value; use ?."
                }
                val declaration = type.declaration as? KSClassDeclaration
                    ?: error("$context: ${type.declaration.qualifiedName?.asString()} has no properties")
                val property = declaration.getAllProperties().firstOrNull { it.simpleName.asString() == segment.name }
                    ?: error("$context: '${segment.name}' is not a property of ${declaration.qualifiedName?.asString()}")
                type = property.type.resolve()
                code += (if (segment.safe) "?." else ".") + identifier(segment.name)
                nullable = segment.safe || type.nullability == Nullability.NULLABLE
            }
            return ResolvedPath(code, type, nullable)
        }
    }

    private class CodeWriter {
        private val output = StringBuilder()
        private val pending = StringBuilder()
        private var depth = 0

        fun static(value: String) {
            pending.append(value)
        }

        fun dynamic(statement: String) {
            flushStatic()
            line(statement)
        }

        fun line(value: String) {
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

        fun flushStatic() {
            if (pending.isEmpty()) return
            output.append("    ".repeat(depth))
                .append("output.append(\"")
                .append(kotlinString(pending.toString()))
                .append("\")\n")
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
        val keywords = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
            "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "var", "when", "while",
        )

        fun identifier(value: String): String = if (value in keywords) "`$value`" else value

        fun kotlinString(value: String): String = buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '$' -> "\\$"
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
