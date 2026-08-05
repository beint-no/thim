package no.beint.thim.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.ClassKind
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
    private val routes: RouteCatalog,
    private val staticContent: StaticContent,
    private val registryName: String,
    private val strictModels: Boolean = false,
) {
    private var generatedVariable = 0
    private var regionalLocales = emptyMap<String, Int>()
    private var languages = emptyMap<String, Int>()
    private var formErrors: ResolvedPath? = null
    val errors = mutableListOf<String>()
    private val usedRootProperties = mutableMapOf<String, MutableSet<String>>()

    fun usedRootProperties(model: KSClassDeclaration): Set<String> =
        usedRootProperties[model.qualifiedName?.asString()] ?: emptySet()

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
                val scope = Scope(model, recordUse = { property ->
                    usedRootProperties.getOrPut(modelName, ::mutableSetOf).add(property)
                })
                formErrors = scope.errorsProperty()
                nodes.forEach { renderNodeCollecting(it, scope, code, templateName) }
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

    /**
     * Collects a failing element's diagnostic and keeps compiling its siblings, so one
     * run reports every error. The partially written renderer is safe to abandon because
     * generation is skipped whenever any error was collected.
     */
    private fun renderNodeCollecting(node: Node, scope: Scope, code: CodeWriter, context: String) {
        try {
            renderNode(node, scope, code, context)
        } catch (exception: IllegalArgumentException) {
            errors.add(exception.message ?: "Thim compilation failed")
        } catch (exception: IllegalStateException) {
            errors.add(exception.message ?: "Thim compilation failed")
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
            if (strictModels) {
                requireDiagnostic(isMaterialized(collection.type), "THIM-EACH-NOT-MATERIALIZED", attributeLocation) {
                    "strict models require a materialized collection (List, Set, Collection, or array) for th:each"
                }
            }
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

        attributes["th:object"]?.let { objectExpression ->
            val attributeLocation = attributeLocation(element, "th:object")
            requireDiagnostic(element.name == "form", "THIM-OBJECT-ELEMENT", attributeLocation) {
                "th:object is only supported on <form> elements"
            }
            requireDiagnostic(!scope.hasSelection(), "THIM-OBJECT-NESTED", attributeLocation) {
                "th:object cannot be nested inside another th:object form"
            }
            val resolved = scope.resolve(
                Expressions.path(objectExpression, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:object")),
                diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:object"),
                attributeLocation,
            )
            requireDiagnostic(!resolved.nullable, "THIM-OBJECT-NULLABLE", attributeLocation) {
                "th:object cannot bind a nullable form object"
            }
            requireDiagnostic(resolved.type.declaration is KSClassDeclaration, "THIM-OBJECT-TYPE", attributeLocation) {
                "th:object must bind a class with properties"
            }
            scope = scope.withSelection(Binding(resolved.code, resolved.type, false))
        }

        if (element.name == "select" && "th:field" in attributes) {
            val attributeLocation = attributeLocation(element, "th:field")
            val path = Expressions.selection(
                requireNotNull(attributes["th:field"]),
                diagnosticContext(attributeLocation, "THIM-FIELD-SYNTAX", "th:field"),
            )
            val resolved = scope.resolveField(path, attributeLocation)
            scope = scope.withSelectValue(Binding(resolved.code, resolved.type, resolved.nullable))
        }

        val transparent = element.name == "th:block"
        var fieldExpansion: FieldExpansion? = null
        if (!transparent) {
            code.static("<${element.name}")
            element.attributes.forEach { (name, value) ->
                if (name.startsWith("th:") || name == "xmlns:th") return@forEach
                if ("th:$name" in element.attributes) return@forEach
                if (name in htmxMethodAttributes && value != null && value.startsWith('/')) {
                    routes.check(value, htmxMethodAttributes.getValue(name), attributeLocation(element, name), name)
                }
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
            if (element.name == "option") renderOptionSelected(element, scope, code)
            attributes["th:field"]?.let { fieldExpansion = renderField(element, it, scope, code) }
            code.static(">")
            fieldExpansion?.trailing?.let(code::static)
            if (element.name == "form" && "th:action" in attributes) {
                renderExtraHiddenFields(code)
            }
        }
        requireDiagnostic(!transparent || "th:field" !in attributes, "THIM-FIELD-ELEMENT", element.location) {
            "th:field is only supported on <input> and <textarea> elements"
        }

        val text = attributes["th:text"]
        val safeHtml = attributes["th:utext"]
        val errorsAttribute = attributes["th:errors"]
        if (errorsAttribute != null) {
            requireDiagnostic(text == null && safeHtml == null && "th:field" !in attributes, "THIM-ERRORS-CONFLICT", element.location) {
                "th:errors replaces the element content; remove th:text, th:utext, and th:field"
            }
            renderErrors(errorsAttribute, element, scope, code)
        } else if (fieldExpansion?.content != null) {
            code.statement("output.text(${fieldExpansion?.content});")
        } else if (text == null && safeHtml == null) {
            element.children.forEach { renderNodeCollecting(it, scope, code, context) }
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
                val expressionUrl = Expressions.url(expression, diagnosticContext(attributeLocation, "THIM-URL-SYNTAX", "th:$name"))
                checkRoute(expressionUrl.path, name, element, attributeLocation)
                code.static(" $name=\"")
                val url = urlCode(expressionUrl, scope, attributeLocation, name)
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
                requireDiagnostic(!isUrlAttribute(name) || message.arguments.isEmpty(), "THIM-URL-MESSAGE-ARGUMENTS", attributeLocation) {
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
                if (isUrlAttribute(name)) {
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
        !isUrlAttribute(name) -> "output.text($value);"
        name == "action" -> "output.text(context.requestDataValues().processAction(context.resolveUrl($value.value()), \"${javaString(formMethod(element))}\"));"
        name == "href" || name == "src" -> "output.text(context.requestDataValues().processUrl(context.resolveUrl($value.value())));"
        else -> "output.text(context.resolveUrl($value.value()));"
    }

    private data class FieldExpansion(val content: String? = null, val trailing: String? = null)

    /**
     * Expands th:field="*{path}" into generated id, name, and the bound value. Text-like
     * inputs get a value attribute (through FormErrors redisplay when the page model has
     * an errors property), textarea gets its value as content, checkbox and radio get
     * checked-state, and select gets id/name with option selection handled per option.
     */
    private fun renderField(element: ElementNode, expression: String, scope: Scope, code: CodeWriter): FieldExpansion? {
        val attributeLocation = attributeLocation(element, "th:field")
        requireDiagnostic(element.name in setOf("input", "textarea", "select"), "THIM-FIELD-ELEMENT", attributeLocation) {
            "th:field is only supported on <input>, <textarea>, and <select> elements"
        }
        val control = when (element.name) {
            "textarea" -> "textarea"
            "select" -> "select"
            else -> {
                requireDiagnostic("th:type" !in element.attributes, "THIM-FIELD-CONTROL", attributeLocation) {
                    "th:field requires a static type attribute"
                }
                element.attributes["type"]?.trim()?.lowercase() ?: "text"
            }
        }
        val conflicts = buildList {
            addAll(listOf("id", "name", "th:id", "th:name", "th:value", "th:text", "th:utext"))
            if (control != "radio") add("value")
        }.filter { it in element.attributes }
        requireDiagnostic(conflicts.isEmpty(), "THIM-FIELD-CONFLICT", attributeLocation) {
            "th:field generates id, name, and the value; remove $conflicts"
        }
        requireDiagnostic(control !in unsupportedFieldTypes, "THIM-FIELD-CONTROL", attributeLocation) {
            "th:field does not support type=\"$control\" yet; supported: ${supportedFieldControls.sorted()}"
        }
        requireDiagnostic(control in supportedFieldControls, "THIM-FIELD-CONTROL", attributeLocation) {
            "th:field does not support type=\"$control\"; supported: ${supportedFieldControls.sorted()}"
        }
        requireDiagnostic(control != "select" || "multiple" !in element.attributes, "THIM-FIELD-CONTROL", attributeLocation) {
            "th:field does not support multiple selects"
        }
        val path = Expressions.selection(expression, diagnosticContext(attributeLocation, "THIM-FIELD-SYNTAX", "th:field"))
        requireDiagnostic(path.segments.none { it.safe }, "THIM-FIELD-NULLABLE", attributeLocation) {
            "th:field cannot use ?.; form properties must be non-null"
        }
        val resolved = scope.resolveField(path, attributeLocation)
        requireDiagnostic(!resolved.nullable, "THIM-FIELD-NULLABLE", attributeLocation) {
            "th:field cannot bind a nullable property"
        }
        val typeName = resolved.type.declaration.qualifiedName?.asString()
        val enum = (resolved.type.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
        val shortType = typeName?.substringAfterLast('.')
        when (control) {
            "number" -> requireDiagnostic(typeName in numericTypes, "THIM-FIELD-VALUE-TYPE", attributeLocation) {
                "type=\"number\" requires a numeric property, found $shortType"
            }
            "textarea" -> requireDiagnostic(typeName in stringTypes, "THIM-FIELD-VALUE-TYPE", attributeLocation) {
                "textarea requires a String property, found $shortType"
            }
            "checkbox" -> requireDiagnostic(typeName in booleanFieldTypes, "THIM-FIELD-VALUE-TYPE", attributeLocation) {
                "type=\"checkbox\" requires a Boolean property, found $shortType"
            }
            "radio", "select" -> requireDiagnostic(typeName in stringTypes || typeName in numericTypes || enum, "THIM-FIELD-VALUE-TYPE", attributeLocation) {
                "$control requires a String, numeric, or enum property, found $shortType"
            }
            in temporalFieldTypes.keys -> requireDiagnostic(typeName == temporalFieldTypes.getValue(control), "THIM-FIELD-VALUE-TYPE", attributeLocation) {
                "type=\"$control\" requires a ${temporalFieldTypes.getValue(control)} property, found $typeName"
            }
            else -> requireDiagnostic(typeName in stringTypes || typeName in numericTypes, "THIM-FIELD-VALUE-TYPE", attributeLocation) {
                "type=\"$control\" requires a String or numeric property, found $shortType"
            }
        }
        val fieldName = path.segments.joinToString(".") { it.name }
        when (control) {
            "checkbox" -> {
                code.static(" id=\"${escapeHtml(fieldName)}\" name=\"${escapeHtml(fieldName)}\" value=\"true\"")
                code.open("if (${resolved.code})")
                code.static(" checked")
                code.close()
                // Spring resets unchecked checkboxes through the _field marker convention.
                return FieldExpansion(trailing = "<input type=\"hidden\" name=\"_${escapeHtml(fieldName)}\" value=\"on\">")
            }
            "radio" -> {
                val optionValue = element.attributes["value"]
                    ?: diagnostic("THIM-FIELD-CONTROL", attributeLocation, "type=\"radio\" with th:field needs a static value attribute")
                code.static(" id=\"${escapeHtml("$fieldName.$optionValue")}\" name=\"${escapeHtml(fieldName)}\"")
                code.open("if (\"${javaString(optionValue)}\".equals(String.valueOf(${resolved.code})))")
                code.static(" checked")
                code.close()
                return null
            }
            "select" -> {
                code.static(" id=\"${escapeHtml(fieldName)}\" name=\"${escapeHtml(fieldName)}\"")
                return null
            }
        }
        // BigDecimal.toString() can produce scientific notation, which type="number" rejects.
        val fallback = when {
            typeName == "java.math.BigDecimal" -> "${resolved.code}.toPlainString()"
            typeName in stringTypes -> resolved.code
            else -> "String.valueOf(${resolved.code})"
        }
        val value = formErrors?.let { "${it.code}.value(\"${javaString(fieldName)}\", $fallback)" } ?: fallback
        code.static(" id=\"${escapeHtml(fieldName)}\" name=\"${escapeHtml(fieldName)}\"")
        if (element.name == "textarea") {
            return FieldExpansion(content = value)
        }
        code.static(" value=\"")
        code.statement("output.text($value);")
        code.static("\"")
        return null
    }

    private fun renderErrors(expression: String, element: ElementNode, scope: Scope, code: CodeWriter) {
        val attributeLocation = attributeLocation(element, "th:errors")
        val errors = formErrors
            ?: diagnostic(
                "THIM-ERRORS-MODEL",
                attributeLocation,
                "th:errors needs a non-null 'errors' property of type no.beint.thim.FormErrors on the page model",
            )
        val path = Expressions.selection(expression, diagnosticContext(attributeLocation, "THIM-ERRORS-SYNTAX", "th:errors"))
        scope.resolveField(path, attributeLocation)
        val fieldName = path.segments.joinToString(".") { it.name }
        val messages = "messages${generatedVariable++}"
        val index = "index${generatedVariable++}"
        code.statement("var $messages = ${errors.code}.messages(\"${javaString(fieldName)}\");")
        code.open("for (var $index = 0; $index < $messages.size(); $index++)")
        code.open("if ($index > 0)")
        code.static("<br>")
        code.close()
        code.statement("output.text($messages.get($index));")
        code.close()
    }

    private fun renderOptionSelected(element: ElementNode, scope: Scope, code: CodeWriter) {
        val bound = scope.selectValue() ?: return
        val static = element.attributes["value"]
        val dynamic = element.attributes["th:value"]
        val comparison = when {
            dynamic != null -> {
                val attributeLocation = attributeLocation(element, "th:value")
                val resolved = scope.resolve(
                    Expressions.path(dynamic, diagnosticContext(attributeLocation, "THIM-EXPRESSION-SYNTAX", "th:value")),
                    diagnosticContext(attributeLocation, "THIM-PROPERTY-UNKNOWN", "th:value"),
                    attributeLocation,
                )
                "String.valueOf(${resolved.code}).equals(String.valueOf(${bound.code}))"
            }
            static != null -> "\"${javaString(static)}\".equals(String.valueOf(${bound.code}))"
            else -> diagnostic("THIM-FIELD-CONTROL", element.location, "<option> inside a th:field select needs a value or th:value attribute")
        }
        code.open("if ($comparison)")
        code.static(" selected")
        code.close()
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

    private fun urlCode(url: UrlExpression, scope: Scope, location: SourceLocation?, name: String): String {
        val pieces = mutableListOf<String>()
        val static = StringBuilder()
        fun flush() {
            if (static.isNotEmpty()) {
                pieces.add("\"${javaString(static.toString())}\"")
                static.clear()
            }
        }
        if (url.path.startsWith('/')) pieces.add("context.contextPath()")
        var start = 0
        urlVariablePattern.findAll(url.path).forEach { match ->
            static.append(url.path.substring(start, match.range.first))
            when (val value = url.parameter(match.groupValues[1]).value) {
                is UrlLiteral -> static.append(encodeUrl(value.value))
                is UrlProperty -> {
                    flush()
                    pieces.add(urlArgument(value, match.groupValues[1], scope, location, name, path = true))
                }
            }
            start = match.range.last + 1
        }
        static.append(url.path.substring(start))
        url.queryParameters.forEachIndexed { index, parameter ->
            static.append(if (index == 0) '?' else '&').append(parameter.name).append('=')
            when (val value = parameter.value) {
                is UrlLiteral -> static.append(encodeUrl(value.value))
                is UrlProperty -> {
                    flush()
                    pieces.add(urlArgument(value, parameter.name, scope, location, name, path = false))
                }
            }
        }
        flush()
        return pieces.joinToString(" + ").ifEmpty { "\"\"" }
    }

    private fun urlArgument(
        value: UrlProperty,
        parameterName: String,
        scope: Scope,
        location: SourceLocation?,
        name: String,
        path: Boolean,
    ): String {
        val resolved = scope.resolve(
            value.path,
            diagnosticContext(location, "THIM-PROPERTY-UNKNOWN", "th:$name"),
            location,
        )
        requireDiagnostic(!resolved.nullable, "THIM-URL-ARGUMENT-NULLABLE", location) {
            "URL parameter '$parameterName' cannot be nullable"
        }
        val typeName = resolved.type.declaration.qualifiedName?.asString()
        requireDiagnostic(typeName !in setOf("no.beint.thim.SafeHtml", "no.beint.thim.TrustedUrl"), "THIM-URL-ARGUMENT-TYPE", location) {
            "URL parameter '$parameterName' cannot be a ${typeName?.substringAfterLast('.')}"
        }
        val encoder = if (path) "pathSegment" else "query"
        return "no.beint.thim.UrlEncoding.$encoder(${resolved.code})"
    }

    private fun checkRoute(path: String, name: String, element: ElementNode, location: SourceLocation?) {
        val method = when {
            name == "href" -> "GET"
            name == "action" -> formMethod(element).uppercase()
            name in htmxMethodAttributes -> htmxMethodAttributes.getValue(name)
            else -> return
        }
        routes.check(path, method, location, "th:$name")
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

    private fun isMaterialized(type: KSType): Boolean {
        val candidates = sequenceOf(type) + (type.declaration as? KSClassDeclaration).orEmptySuperTypes()
        return candidates.any {
            it.declaration.qualifiedName?.asString() in setOf(
                "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
                "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Set",
                "kotlin.collections.MutableSet", "kotlin.Array", "java.util.Collection",
                "java.util.List", "java.util.Set",
            )
        }
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
        private val recordUse: (String) -> Unit = {},
        private val bindings: Map<String, Binding> = emptyMap(),
        private val selection: Binding? = null,
        private val select: Binding? = null,
    ) {
        fun withBinding(name: String, binding: Binding) = Scope(model, recordUse, bindings + (name to binding), selection, select)

        fun withSelection(binding: Binding) = Scope(model, recordUse, bindings, binding, select)

        fun withSelectValue(binding: Binding) = Scope(model, recordUse, bindings, selection, binding)

        fun hasSelection(): Boolean = selection != null

        fun selectValue(): Binding? = select

        fun errorsProperty(): ResolvedPath? {
            val property = model.property("errors") ?: return null
            val typeName = property.type.declaration.qualifiedName?.asString()
            if (typeName != "no.beint.thim.FormErrors" || property.type.nullability == Nullability.NULLABLE) return null
            recordUse("errors")
            return ResolvedPath("model.${property.accessor}()", property.type, false)
        }

        fun resolveField(expression: PathExpression, location: SourceLocation?): ResolvedPath {
            val selected = selection
                ?: diagnostic("THIM-FIELD-SCOPE", location, "*{...} requires an enclosing <form th:object=\"...\">")
            var code = selected.code
            var type = selected.type
            var nullable = selected.nullable
            expression.segments.forEach { segment ->
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
                val access = "$code.${property.accessor}()"
                code = if (segment.safe) "($code == null ? null : $access)" else access
                type = property.type
                nullable = segment.safe || property.type.nullability == Nullability.NULLABLE
            }
            return ResolvedPath(code, type, nullable)
        }

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
                recordUse(first.name)
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
        val controlAttributes = setOf("th:text", "th:utext", "th:each", "th:if", "th:unless", "th:fragment", "th:object", "th:field", "th:errors")
        val urlAttributes = setOf("action", "cite", "data", "formaction", "href", "poster", "src", "srcset")
        val htmxMethodAttributes = mapOf(
            "hx-get" to "GET",
            "hx-post" to "POST",
            "hx-put" to "PUT",
            "hx-patch" to "PATCH",
            "hx-delete" to "DELETE",
        )
        val urlVariablePattern = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")

        fun isUrlAttribute(name: String): Boolean = name in urlAttributes || name in htmxMethodAttributes

        fun encodeUrl(value: String): String = buildString {
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val code = byte.toInt() and 0xff
                val character = code.toChar()
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                    character == '-' || character == '.' || character == '_' || character == '~'
                ) {
                    append(character)
                } else {
                    append('%').append("%02X".format(code))
                }
            }
        }
        val rawTextElements = setOf("script", "style")
        val unsupportedAttributes = setOf(
            "th:attr", "th:case", "th:classappend", "th:id", "th:inline", "th:insert",
            "th:remove", "th:replace", "th:switch", "th:with",
        )
        val textFieldTypes = setOf("text", "hidden", "email", "password", "search", "tel", "url")
        val unsupportedFieldTypes = setOf("file", "color", "range", "month", "week")
        val temporalFieldTypes = mapOf(
            "date" to "java.time.LocalDate",
            "time" to "java.time.LocalTime",
            "datetime-local" to "java.time.LocalDateTime",
        )
        val supportedFieldControls =
            textFieldTypes + temporalFieldTypes.keys + setOf("number", "textarea", "select", "checkbox", "radio")
        val booleanFieldTypes = setOf("kotlin.Boolean", "java.lang.Boolean", "boolean")
        val stringTypes = setOf("kotlin.String", "java.lang.String")
        val numericTypes = setOf(
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "int", "long", "short", "byte", "double", "float",
            "java.math.BigDecimal", "java.math.BigInteger",
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
