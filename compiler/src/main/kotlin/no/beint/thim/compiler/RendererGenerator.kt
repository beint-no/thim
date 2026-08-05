package no.beint.thim.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
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
        val location = "${element.location} THIM-TEMPLATE-ELEMENT <$context:${element.name}>"
        val attributes = element.attributes
        val unsupported = attributes.keys.filter { it in unsupportedAttributes }
        requireDiagnostic(unsupported.isEmpty(), "THIM-ATTRIBUTE-UNSUPPORTED", element.location) {
            "<${element.name}> has unsupported attributes $unsupported"
        }
        requireDiagnostic(!(attributes.containsKey("th:if") && attributes.containsKey("th:unless")), "THIM-CONDITION-CONFLICT", element.location) {
            "<${element.name}> cannot combine th:if and th:unless"
        }
        requireDiagnostic(!(attributes.containsKey("th:text") && attributes.containsKey("th:utext")), "THIM-TEXT-CONFLICT", element.location) {
            "<${element.name}> cannot combine th:text and th:utext"
        }
        requireDiagnostic(
            element.name !in rawTextElements || !(attributes.containsKey("th:text") || attributes.containsKey("th:utext")),
            "THIM-CONTEXT-UNSUPPORTED",
            element.location,
        ) {
            val language = if (element.name == "script") "JavaScript" else "CSS"
            "<${element.name}> content is a $language context; dynamic output is not supported"
        }

        var scope = parentScope
        var blocks = 0
        attributes["th:each"]?.let { each ->
            val attributeLocation = attributeLocation(element, "th:each")
            val match = eachPattern.matchEntire(each.trim())
                ?: diagnostic("THIM-EACH-SYNTAX", attributeLocation, "expected 'item : \${items}'")
            val variable = match.groupValues[1]
            val collection = scope.resolve(
                Expressions.path(match.groupValues[2], diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:each")),
                diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:each"),
                attributeLocation,
            )
            requireDiagnostic(!collection.nullable, "THIM-EACH-NULLABLE", attributeLocation) {
                "th:each collection cannot be nullable"
            }
            val elementType = iterableElement(collection.type)
                ?: diagnostic("THIM-EACH-NOT-ITERABLE", attributeLocation, "'${match.groupValues[2]}' is not iterable")
            val generatedName = "item${generatedVariable++}"
            code.open("for (var $generatedName : ${collection.code})")
            scope = scope.withBinding(variable, Binding(generatedName, elementType, elementType.nullability == Nullability.NULLABLE))
            blocks++
        }

        attributes["th:if"]?.let { condition ->
            val attributeLocation = attributeLocation(element, "th:if")
            val literal = condition.trim().toBooleanStrictOrNull()
            if (literal != null) {
                code.open("if ($literal)")
                blocks++
            } else {
                val resolved = scope.resolve(
                    Expressions.path(condition, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:if")),
                    diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:if"),
                    attributeLocation,
                )
                requireDiagnostic(resolved.type.isBoolean() && !resolved.nullable, "THIM-CONDITION-TYPE", attributeLocation) {
                    "th:if requires a non-null Boolean"
                }
                code.open("if (${resolved.code})")
                blocks++
            }
        }
        attributes["th:unless"]?.let { condition ->
            val attributeLocation = attributeLocation(element, "th:unless")
            val literal = condition.trim().toBooleanStrictOrNull()
            if (literal != null) {
                code.open("if (!${literal})")
                blocks++
            } else {
                val resolved = scope.resolve(
                    Expressions.path(condition, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:unless")),
                    diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:unless"),
                    attributeLocation,
                )
                requireDiagnostic(resolved.type.isBoolean() && !resolved.nullable, "THIM-CONDITION-TYPE", attributeLocation) {
                    "th:unless requires a non-null Boolean"
                }
                code.open("if (!${resolved.code})")
                blocks++
            }
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
                    renderAttribute(
                        name.removePrefix("th:"),
                        requireNotNull(expression),
                        element,
                        scope,
                        code,
                        location,
                    )
                }
            }
            code.static(">")
            if (element.name == "form" && "th:action" in attributes) {
                renderExtraHiddenFields(code)
            }
        }

        val text = attributes["th:text"]
        val safeHtml = attributes["th:utext"]
        if (text == null && safeHtml == null) {
            element.children.forEach { renderNode(it, scope, code, context) }
        } else if (safeHtml != null) {
            val attributeLocation = attributeLocation(element, "th:utext")
            if (safeHtml.trim().startsWith("#{")) {
                val message = Expressions.message(safeHtml, diagnosticContext(attributeLocation, "THIM-MESSAGE-SYNTAX", "th:utext"))
                requireDiagnostic(message.arguments.isEmpty(), "THIM-RAW-MESSAGE-ARGUMENTS", attributeLocation) {
                    "raw messages cannot contain arguments"
                }
                renderMessage(message, scope, code, diagnosticContext(attributeLocation, "THIM-MESSAGE", "th:utext"), attributeLocation, raw = true)
            } else {
                val value = scope.resolve(
                    Expressions.path(safeHtml, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:utext")),
                    diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:utext"),
                    attributeLocation,
                )
                requireDiagnostic(
                    value.type.declaration.qualifiedName?.asString() == "no.beint.thim.SafeHtml" && !value.nullable,
                    "THIM-RAW-HTML-TYPE",
                    attributeLocation,
                ) {
                    "th:utext requires a non-null no.beint.thim.SafeHtml property"
                }
                code.statement("output.raw(${value.code});")
            }
        } else if (requireNotNull(text).trim().startsWith("#{")) {
            val attributeLocation = attributeLocation(element, "th:text")
            renderMessage(
                Expressions.message(text, diagnosticContext(attributeLocation, "THIM-MESSAGE-SYNTAX", "th:text")),
                scope,
                code,
                diagnosticContext(attributeLocation, "THIM-MESSAGE", "th:text"),
                attributeLocation,
            )
        } else {
            val attributeLocation = attributeLocation(element, "th:text")
            val value = scope.resolve(
                Expressions.path(requireNotNull(text), diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:text")),
                diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:text"),
                attributeLocation,
            )
            val typeName = value.type.declaration.qualifiedName?.asString()
            requireDiagnostic(typeName != "no.beint.thim.SafeHtml", "THIM-TEXT-TYPE", attributeLocation) {
                "th:text escapes its value; render SafeHtml with th:utext"
            }
            requireDiagnostic(typeName != "no.beint.thim.TrustedUrl", "THIM-TEXT-TYPE", attributeLocation) {
                "TrustedUrl is only supported in URL attributes"
            }
            code.statement("output.text(${value.code});")
        }

        if (!transparent && element.name !in voidElements) code.static("</${element.name}>")
        repeat(blocks) { code.close() }
    }

    private fun renderAttribute(
        name: String,
        expression: String,
        element: ElementNode,
        scope: Scope,
        code: CodeWriter,
        context: String,
    ) {
        val attributeLocation = attributeLocation(element, "th:$name")
        val location = diagnosticContext(attributeLocation, "THIM-ATTRIBUTE", "th:$name")
        requireDiagnostic(!name.startsWith("on"), "THIM-CONTEXT-UNSUPPORTED", attributeLocation) {
            "th:$name is an event-handler JavaScript context; dynamic output is not supported, use a static attribute and data-* values"
        }
        requireDiagnostic(name != "style", "THIM-CONTEXT-UNSUPPORTED", attributeLocation) {
            "th:style is a CSS context; dynamic output is not supported, use a static style attribute or conditional classes"
        }
        if (name in booleanAttributes) {
            expression.trim().toBooleanStrictOrNull()?.let { literal ->
                if (literal) code.static(" $name")
                return
            }
            val value = scope.resolve(
                Expressions.path(expression, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:$name")),
                diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:$name"),
                attributeLocation,
            )
            requireDiagnostic(value.type.isBoolean() && !value.nullable, "THIM-BOOLEAN-ATTRIBUTE-TYPE", attributeLocation) {
                "th:$name requires a non-null Boolean"
            }
            code.open("if (${value.code})")
            code.static(" $name")
            code.close()
            return
        }

        when {
            expression.trim().toBooleanStrictOrNull() != null -> {
                code.static(" $name=\"")
                code.static(expression.trim())
                code.static("\"")
            }
            expression.trim().startsWith("@{") -> {
                val path = parseUrl(expression, location)
                code.static(" $name=\"")
                val url = if (path.startsWith('/')) {
                    "context.contextPath() + \"${javaString(path)}\""
                } else {
                    "\"${javaString(path)}\""
                }
                when (name) {
                    "action" -> {
                        val method = formMethod(element)
                        code.statement("output.text(context.requestDataValues().processAction($url, \"${javaString(method)}\"));")
                    }
                    "href", "src" -> code.statement("output.text(context.requestDataValues().processUrl($url));")
                    else -> code.statement("output.text($url);")
                }
                code.static("\"")
            }
            expression.trim().startsWith("#{") -> {
                val message = Expressions.message(expression, diagnosticContext(attributeLocation, "THIM-MESSAGE-SYNTAX", "th:$name"))
                requireDiagnostic(name !in urlAttributes || message.arguments.isEmpty(), "THIM-URL-MESSAGE-ARGUMENTS", attributeLocation) {
                    "th:$name is a URL context; messages with dynamic arguments are not supported"
                }
                code.static(" $name=\"")
                renderMessage(
                    message,
                    scope,
                    code,
                    diagnosticContext(attributeLocation, "THIM-MESSAGE", "th:$name"),
                    attributeLocation,
                )
                code.static("\"")
            }
            expression.trim().startsWith("\${") -> {
                if (expression.trim() == "\${#locale.language}") {
                    code.static(" $name=\"")
                    code.statement("output.text(context.locale().getLanguage());")
                    code.static("\"")
                    return
                }
                val value = scope.resolve(
                    Expressions.path(expression, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:$name")),
                    diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:$name"),
                    attributeLocation,
                )
                val typeName = value.type.declaration.qualifiedName?.asString()
                if (name in urlAttributes) {
                    requireDiagnostic(typeName == "no.beint.thim.TrustedUrl", "THIM-URL-TYPE", attributeLocation) {
                        "th:$name is a URL context; use a static @{...} URL or a no.beint.thim.TrustedUrl property"
                    }
                } else {
                    requireDiagnostic(typeName != "no.beint.thim.SafeHtml", "THIM-ATTRIBUTE-TYPE", attributeLocation) {
                        "SafeHtml is not supported in attribute values"
                    }
                    requireDiagnostic(typeName != "no.beint.thim.TrustedUrl", "THIM-ATTRIBUTE-TYPE", attributeLocation) {
                        "TrustedUrl is only supported in URL attributes"
                    }
                }
                if (value.nullable) {
                    val variable = "attribute${generatedVariable++}"
                    code.statement("var $variable = ${value.code};")
                    code.open("if ($variable != null)")
                    code.static(" $name=\"")
                    code.statement(attributeWrite(name, element, variable))
                    code.static("\"")
                    code.close()
                } else {
                    code.static(" $name=\"")
                    code.statement(attributeWrite(name, element, value.code))
                    code.static("\"")
                }
            }
            expression.length >= 2 && expression.first() == '\'' && expression.last() == '\'' ->
                code.static(" $name=\"${escapeHtml(expression.substring(1, expression.length - 1))}\"")
            else -> diagnostic("THIM-ATTRIBUTE-EXPRESSION", attributeLocation, "expected a property, message, URL, or quoted literal")
        }
    }

    private fun attributeWrite(name: String, element: ElementNode, value: String): String = when {
        name !in urlAttributes -> "output.text($value);"
        name == "action" -> "output.text(context.requestDataValues().processAction($value.value(), \"${javaString(formMethod(element))}\"));"
        name == "href" || name == "src" -> "output.text(context.requestDataValues().processUrl($value.value()));"
        else -> "output.url($value);"
    }

    private fun renderExtraHiddenFields(code: CodeWriter) {
        code.open("for (var field : context.requestDataValues().extraHiddenFields().entrySet())")
        code.static("<input type=\"hidden\" name=\"")
        code.statement("output.text(field.getKey());")
        code.static("\" value=\"")
        code.statement("output.text(field.getValue());")
        code.static("\">")
        code.close()
    }

    private fun formMethod(element: ElementNode): String {
        val method = element.attributes["method"]?.trim()?.lowercase() ?: "get"
        return if (method in setOf("get", "post")) method else "get"
    }

    private fun renderMessage(
        expression: MessageExpression,
        scope: Scope,
        code: CodeWriter,
        context: String,
        location: SourceLocation?,
        raw: Boolean = false,
    ) {
        val definition = catalog.use(expression.key, expression.arguments.size, context)
        val arguments = expression.arguments.map {
            scope.resolve(it, diagnosticContext(location, "THIM-PROPERTY-UNKNOWN", "message argument"), location).code
        }
        val localized = definition.localized.filterValues { it != definition.base }
        val regional = localized.filterKeys { '-' in it }
        val languageValues = localized.filterKeys { '-' !in it }

        if (regional.isNotEmpty()) {
            code.open("switch (locale)")
            regional.forEach { (locale, value) ->
                code.open("case ${regionalLocales.getValue(locale)} ->")
                appendMessage(value, arguments, code, raw)
                code.close()
            }
            code.open("default ->")
        }
        if (languageValues.isNotEmpty()) {
            code.open("switch (language)")
            languageValues.forEach { (locale, value) ->
                code.open("case ${languages.getValue(locale)} ->")
                appendMessage(value, arguments, code, raw)
                code.close()
            }
            code.open("default ->")
            appendMessage(definition.base, arguments, code, raw)
            code.close()
            code.close()
        } else {
            appendMessage(definition.base, arguments, code, raw)
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

    private fun appendMessage(pattern: String, arguments: List<String>, code: CodeWriter, raw: Boolean = false) {
        var start = 0
        placeholderPattern.findAll(pattern).forEach { match ->
            code.static(if (raw) pattern.substring(start, match.range.first) else escapeHtml(pattern.substring(start, match.range.first)))
            code.statement("output.text(${arguments[match.groupValues[1].toInt()]});")
            start = match.range.last + 1
        }
        code.static(if (raw) pattern.substring(start) else escapeHtml(pattern.substring(start)))
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

    private fun attributeLocation(element: ElementNode, name: String): SourceLocation =
        element.attributeLocations[name] ?: element.location

    private fun diagnosticContext(location: SourceLocation?, code: String, subject: String): String =
        buildString {
            if (location != null) append(location).append(' ')
            append(code).append(' ').append(subject)
        }

    private fun iterableElement(type: KSType): KSType? {
        val candidates = sequenceOf(type) + (type.declaration as? KSClassDeclaration).orEmptySuperTypes()
        return candidates.firstOrNull {
            it.declaration.qualifiedName?.asString() in setOf(
                "kotlin.collections.Iterable", "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
                "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Set",
                "kotlin.collections.MutableSet", "kotlin.Array", "java.lang.Iterable", "java.util.Collection",
                "java.util.List", "java.util.Set",
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

    private data class Property(val type: KSType, val accessor: String)

    private class Scope(
        private val model: KSClassDeclaration,
        private val bindings: Map<String, Binding> = emptyMap(),
    ) {
        fun withBinding(name: String, binding: Binding) = Scope(model, bindings + (name to binding))

        fun resolve(expression: PathExpression, context: String, location: SourceLocation?): ResolvedPath {
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
                val property = model.property(first.name)
                    ?: diagnostic(
                        "THIM-PROPERTY-UNKNOWN",
                        location,
                        "'${first.name}' is not a property of ${model.qualifiedName?.asString()}${model.suggestion(first.name)}",
                    )
                type = property.type
                nullable = type.nullability == Nullability.NULLABLE
                code = "model.${property.accessor}()"
            }

            expression.segments.drop(1).forEach { segment ->
                requireDiagnostic(!nullable || segment.safe, "THIM-PROPERTY-NULLABLE-DEREFERENCE", location) {
                    "'${segment.name}' dereferences a nullable value; use ?."
                }
                val declaration = type.declaration as? KSClassDeclaration
                    ?: diagnostic("THIM-PROPERTY-NOT-OBJECT", location, "${type.declaration.qualifiedName?.asString()} has no properties")
                val property = declaration.property(segment.name)
                    ?: diagnostic(
                        "THIM-PROPERTY-UNKNOWN",
                        location,
                        "'${segment.name}' is not a property of ${declaration.qualifiedName?.asString()}${declaration.suggestion(segment.name)}",
                    )
                val nextType = property.type
                val access = "$code.${property.accessor}()"
                code = if (segment.safe) "($code == null ? null : $access)" else access
                type = nextType
                nullable = segment.safe || type.nullability == Nullability.NULLABLE
            }
            return ResolvedPath(code, type, nullable)
        }

        private fun KSClassDeclaration.property(name: String): Property? {
            getAllProperties().firstOrNull { it.simpleName.asString() == name }?.let { property ->
                val type = property.type.resolve()
                return Property(type, getter(name, type))
            }
            primaryConstructor?.parameters?.firstOrNull { it.name?.asString() == name }?.let { component ->
                return Property(component.type.resolve(), name)
            }
            val capitalized = name.replaceFirstChar(Char::uppercaseChar)
            val accessors = listOf(name, "get$capitalized", "is$capitalized")
            val functions = getDeclaredFunctions() + getAllSuperTypes().flatMap { type ->
                (type.declaration as? KSClassDeclaration)?.getDeclaredFunctions() ?: emptySequence()
            }
            val function = functions.firstOrNull { candidate ->
                candidate.simpleName.asString() in accessors &&
                    candidate.parameters.isEmpty() &&
                    candidate.returnType != null
            } ?: return null
            return Property(function.returnType!!.resolve(), function.simpleName.asString())
        }

        private fun KSClassDeclaration.suggestion(name: String): String {
            val nearest = propertyNames().minByOrNull { distance(name, it) } ?: return ""
            return if (distance(name, nearest) <= 2) "; did you mean '$nearest'?" else ""
        }

        private fun KSClassDeclaration.propertyNames(): Set<String> = buildSet {
            getAllProperties().forEach { add(it.simpleName.asString()) }
            primaryConstructor?.parameters?.forEach { parameter ->
                parameter.name?.asString()?.let(::add)
            }
            getDeclaredFunctions().forEach { function ->
                val name = function.simpleName.asString()
                when {
                    name.startsWith("get") && name.length > 3 -> add(name.substring(3).replaceFirstChar(Char::lowercaseChar))
                    name.startsWith("is") && name.length > 2 -> add(name)
                }
            }
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

        private fun getter(name: String, type: KSType): String {
            val boolean = type.isBoolean()
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
        val booleanAttributes = setOf(
            "allowfullscreen", "async", "autofocus", "autoplay", "checked", "controls", "default", "defer",
            "disabled", "formnovalidate", "hidden", "inert", "ismap", "itemscope", "loop", "multiple",
            "muted", "nomodule", "novalidate", "open", "playsinline", "readonly", "required", "reversed", "selected",
        )
        val controlAttributes = setOf("th:text", "th:utext", "th:each", "th:if", "th:unless", "th:fragment")
        val urlAttributes = setOf("action", "cite", "data", "formaction", "href", "poster", "src", "srcset")
        val rawTextElements = setOf("script", "style")
        val unsupportedAttributes = setOf(
            "th:attr", "th:case", "th:classappend", "th:errors", "th:field", "th:inline", "th:insert",
            "th:object", "th:remove", "th:replace", "th:switch", "th:with",
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

private fun KSType.isBoolean(): Boolean =
    declaration.qualifiedName?.asString() in setOf("kotlin.Boolean", "java.lang.Boolean", "boolean")
