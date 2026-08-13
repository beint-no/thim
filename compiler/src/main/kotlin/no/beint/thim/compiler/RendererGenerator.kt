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
    private val ranges = hashMapOf<String, IntRange>()

    fun append(value: String): IntRange = ranges.getOrPut(value) {
        val start = output.size()
        output.writeBytes(value.toByteArray(StandardCharsets.UTF_8))
        start until output.size()
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
    private var generatedHelper = 0
    private var regionalLocales = emptyMap<String, Int>()
    private var languages = emptyMap<String, Int>()
    private var formErrors: ResolvedPath? = null
    private var hasMessageLocale = false
    private val pendingHelpers = ArrayDeque<RenderHelper>()
    val errors = mutableListOf<String>()
    private val usedRootProperties = mutableMapOf<String, MutableSet<String>>()

    fun usedRootProperties(model: KSClassDeclaration): Set<String> =
        usedRootProperties[model.qualifiedName?.asString()] ?: emptySet()

    fun compile(templateName: String, model: KSClassDeclaration, nodes: List<Node>): CompiledTemplate {
        val modelName = model.qualifiedName?.asString() ?: error("$templateName: model must have a qualified name")
        val rendererName = modelName.replace(Regex("[^A-Za-z0-9_]"), "_") + "ThimRenderer"
        val code = CodeWriter(staticContent, registryName)
        val locales = if (usesMessages(nodes)) {
            catalog.supportedLocales.filterTo(linkedSetOf()) { it != catalog.defaultLocale }
        } else {
            emptySet()
        }
        val localeIds = if (locales.isEmpty()) {
            emptyMap()
        } else {
            buildMap {
                put(catalog.defaultLocale, 0)
                locales.forEachIndexed { index, locale -> put(locale, index + 1) }
            }
        }
        regionalLocales = localeIds.filterKeys { '-' in it }
        languages = localeIds.filterKeys { '-' !in it }
        hasMessageLocale = regionalLocales.isNotEmpty() || languages.isNotEmpty()
        generatedVariable = 0
        generatedHelper = 0
        pendingHelpers.clear()

        code.line("final class $rendererName {")
        code.indent {
            code.line("private $rendererName() {}")
            code.line()
            code.line("static void render($modelName model, RenderContext context, HtmlOutput output) throws IOException {")
            code.indent {
                if (regionalLocales.isNotEmpty() || languages.isNotEmpty()) {
                    val languageFallback = if (languages.isNotEmpty()) {
                        val cases = languages.entries.joinToString(" ") { (locale, index) ->
                            "case \"${javaString(locale)}\" -> $index;"
                        }
                        "switch (context.locale().getLanguage()) { $cases default -> 0; }"
                    } else {
                        "0"
                    }
                    val resolution = if (regionalLocales.isNotEmpty()) {
                        val cases = regionalLocales.entries.joinToString(" ") { (locale, index) ->
                            "case \"${javaString(locale)}\" -> $index;"
                        }
                        "switch (context.locale().toLanguageTag()) { $cases default -> $languageFallback; }"
                    } else {
                        languageFallback
                    }
                    code.statement("var messageLocale = $resolution;")
                }
                val scope = Scope(model, recordUse = { property ->
                    usedRootProperties.getOrPut(modelName, ::mutableSetOf).add(property)
                })
                formErrors = scope.errorsProperty()
                renderNodes(nodes, scope, code, templateName)
            }
            code.line("}")
            while (pendingHelpers.isNotEmpty()) {
                val helper = pendingHelpers.removeFirst()
                code.line()
                val localeParameter = if (hasMessageLocale) ", int messageLocale" else ""
                val capturedParameters = helper.captures.joinToString("") { ", Object ${it.code}Value" }
                code.line(
                    "private static void ${helper.name}($modelName model, RenderContext context, " +
                        "HtmlOutput output$localeParameter$capturedParameters) throws IOException {",
                )
                code.indent {
                    helper.captures.forEach { binding ->
                        code.statement("var ${binding.code} = (${binding.castType()}) ${binding.code}Value;")
                    }
                    helper.nodes.forEach { renderNodeCollecting(it, helper.scope, code, helper.context) }
                }
                code.line("}")
            }
        }
        code.line("}")
        return CompiledTemplate(model, rendererName, code.toString())
    }

    private fun renderNodes(nodes: List<Node>, scope: Scope, code: CodeWriter, context: String) {
        val chunks = partition(nodes)
        if (chunks == null) {
            nodes.forEach { renderNodeCollecting(it, scope, code, context) }
            return
        }
        val captures = scope.capturedBindings()
        chunks.forEach { chunk ->
            val name = "renderPart${generatedHelper++}"
            pendingHelpers.addLast(RenderHelper(name, chunk, scope, context, captures))
            val localeArgument = if (hasMessageLocale) ", messageLocale" else ""
            val capturedArguments = captures.joinToString("") { ", ${it.code}" }
            code.statement("$name(model, context, output$localeArgument$capturedArguments);")
        }
    }

    private fun partition(nodes: List<Node>): List<List<Node>>? {
        if (nodes.sumOf { it.renderWeight() } <= MAX_RENDER_PART_WEIGHT) return null
        val result = mutableListOf<List<Node>>()
        var current = mutableListOf<Node>()
        var currentWeight = 0
        nodes.forEach { node ->
            val weight = node.renderWeight()
            if (weight > 0 && currentWeight > 0 && currentWeight + weight > MAX_RENDER_PART_WEIGHT) {
                result += current
                current = mutableListOf()
                currentWeight = 0
            }
            current += node
            currentWeight += weight
        }
        if (current.isNotEmpty()) result += current
        return result.takeIf { it.size > 1 }
    }

    private fun renderNode(node: Node, scope: Scope, code: CodeWriter, context: String) {
        when (node) {
            is RawNode -> code.static(node.value)
            is ElementNode -> renderElement(node, scope, code, context)
        }
    }

    /**
     * The partially written renderer is safe to abandon because generation is skipped
     * whenever any error was collected.
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
            val generatedName = "object${generatedVariable++}"
            code.statement("var $generatedName = ${resolved.code};")
            scope = scope.withSelection(Binding(generatedName, resolved.type, false))
        }

        if (element.name == "select" && "th:field" in attributes) {
            val attributeLocation = attributeLocation(element, "th:field")
            val path = Expressions.selection(
                requireNotNull(attributes["th:field"]),
                diagnosticContext(attributeLocation, "THIM-FIELD-SYNTAX", "th:field"),
            )
            val resolved = scope.resolveField(path, attributeLocation)
            val generatedName = "select${generatedVariable++}"
            code.statement("var $generatedName = ${resolved.code};")
            scope = scope.withSelectValue(Binding(generatedName, resolved.type, resolved.nullable))
        }

        val transparent = element.name == "th:block"
        var fieldExpansion: FieldExpansion? = null
        if (!transparent) {
            code.static("<${element.name}")
            if (element.name == "html" && "lang" !in attributes && "th:lang" !in attributes) {
                code.static(" lang=\"")
                code.statement("output.text(context.locale().getLanguage());")
                code.static("\"")
            }
            element.attributes.forEach { (name, value) ->
                if (name.startsWith("th:") || name == "xmlns:th") return@forEach
                if ("th:$name" in element.attributes) return@forEach
                if (name in htmxMethodAttributes && value != null && value.startsWith('/')) {
                    routes.check(value, htmxMethodAttributes.getValue(name), attributeLocation(element, name), name)
                }
                code.static(" $name")
                if (value != null) code.static("=\"${staticAttributeValue(value)}\"")
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
            code.statement("output.text(${fieldExpansion.content});")
        } else if (text == null && safeHtml == null) {
            renderNodes(element.children, scope, code, context)
        } else if (safeHtml != null) {
            val attributeLocation = attributeLocation(element, "th:utext")
            if (safeHtml.trim().startsWith("#{")) {
                diagnostic(
                    "THIM-RAW-MESSAGE-UNSUPPORTED",
                    attributeLocation,
                    "localized messages are escaped text; use a non-null SafeHtml model property for th:utext",
                )
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
                checkRoute(expressionUrl, name, element, scope, attributeLocation)
                code.static(" $name=\"")
                val url = urlCode(expressionUrl, scope, attributeLocation, name, code)
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
        val enum = resolved.type.isEnum()
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
        val value = formErrors?.let {
            scope.recordRootProperty("errors")
            "${it.code}.value(\"${javaString(fieldName)}\", $fallback)"
        } ?: fallback
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
        scope.recordRootProperty("errors")
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
    ) {
        val definition = catalog.use(expression.key, expression.arguments.keys, context)
        val arguments = expression.arguments.mapValues { (name, path) ->
            val resolved = scope.resolve(path, diagnosticContext(location, "THIM-PROPERTY-UNKNOWN", "message argument '$name'"), location)
            requireDiagnostic(!resolved.nullable, "THIM-MESSAGE-ARGUMENT-NULLABLE", location) {
                "message argument '$name' cannot be nullable"
            }
            when (definition.arguments.getValue(name)) {
                MessageArgumentKind.NUMBER -> requireDiagnostic(
                    resolved.type.declaration.qualifiedName?.asString() in integralMessageTypes,
                    "THIM-MESSAGE-ARGUMENT-TYPE",
                    location,
                ) {
                    "message argument '$name' is used for plural selection and requires an integral numeric property"
                }
                MessageArgumentKind.SELECT -> requireDiagnostic(
                    resolved.type.declaration.qualifiedName?.asString() in stringTypes || resolved.type.isEnum(),
                    "THIM-MESSAGE-ARGUMENT-TYPE",
                    location,
                ) {
                    "message argument '$name' is used for selection and requires a String or enum property"
                }
                MessageArgumentKind.TEXT -> requireDiagnostic(
                    resolved.type.declaration.qualifiedName?.asString() in messageTextTypes || resolved.type.isEnum(),
                    "THIM-MESSAGE-ARGUMENT-TYPE",
                    location,
                ) {
                    "message argument '$name' requires a String, number, Boolean, or enum property; prepare other display values in the page model"
                }
            }
            if (definition.arguments.getValue(name) == MessageArgumentKind.SELECT && resolved.type.isEnum()) {
                val declaration = resolved.type.declaration as KSClassDeclaration
                val constants = declaration.declarations
                    .filterIsInstance<KSClassDeclaration>()
                    .filter { it.classKind == ClassKind.ENUM_ENTRY }
                    .map { it.simpleName.asString() }
                    .toSet()
                val variants = definition.values.values.flatMapTo(linkedSetOf()) { it.selectVariants(name) }
                val unknown = variants - constants
                requireDiagnostic(unknown.isEmpty(), "THIM-MESSAGE-SELECT-VARIANT", location) {
                    "message argument '$name' has variants ${unknown.sorted()} that are not constants of " +
                        (declaration.qualifiedName?.asString() ?: declaration.simpleName.asString())
                }
            }
            resolved
        }
        val defaultValue = definition.values.getValue(catalog.defaultLocale)
        val localized = definition.values
            .filterKeys { it != catalog.defaultLocale }
            .filterValues { it != defaultValue || it.usesPlural() }
        if (localized.isEmpty()) {
            renderMessageValue(defaultValue, arguments, code, catalog.defaultLocale)
            return
        }
        code.open("switch (messageLocale)")
        localized.forEach { (locale, value) ->
            val localeId = regionalLocales[locale] ?: languages.getValue(locale)
            code.open("case $localeId ->")
            renderMessageValue(value, arguments, code, locale)
            code.close()
        }
        code.open("default ->")
        renderMessageValue(defaultValue, arguments, code, catalog.defaultLocale)
        code.close()
        code.close()
    }

    private fun usesMessages(nodes: List<Node>): Boolean = nodes.any { node ->
        node is ElementNode && (
            node.attributes.values.any { it?.trim()?.startsWith("#{") == true } || usesMessages(node.children)
        )
    }

    private fun renderMessageValue(
        value: MessageValue,
        arguments: Map<String, ResolvedPath>,
        code: CodeWriter,
        locale: String,
    ) {
        when (value) {
            is MessagePattern -> value.parts.forEach { part ->
                when (part) {
                    is MessageText -> code.static(escapeHtml(part.value))
                    is MessageArgument -> code.statement("output.text(${arguments.getValue(part.name).code});")
                }
            }
            is MessageSelection -> {
                val argument = arguments.getValue(value.argument)
                when (value.kind) {
                    MessageArgumentKind.NUMBER -> {
                        val configured = java.util.Locale.forLanguageTag(locale)
                        code.open(
                            "switch (no.beint.thim.PluralRules.cardinal(" +
                                "\"${javaString(configured.language)}\", \"${javaString(configured.country)}\", ${argument.code}))",
                        )
                    }
                    MessageArgumentKind.SELECT -> {
                        val selector = if (argument.type.isEnum()) "${argument.code}.name()" else argument.code
                        code.open("switch ($selector)")
                    }
                    MessageArgumentKind.TEXT -> error("Text arguments cannot select message variants")
                }
                value.variants.filterKeys { it != "other" }.forEach { (variant, selected) ->
                    code.open("case \"${javaString(variant)}\" ->")
                    renderMessageValue(selected, arguments, code, locale)
                    code.close()
                }
                code.open("default ->")
                renderMessageValue(value.variants.getValue("other"), arguments, code, locale)
                code.close()
                code.close()
            }
        }
    }

    private fun urlCode(
        url: UrlExpression,
        scope: Scope,
        location: SourceLocation?,
        name: String,
        code: CodeWriter,
    ): String {
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
                    val argument = urlArgument(value, match.groupValues[1], scope, location, name, path = true)
                    pieces.add("no.beint.thim.UrlEncoding.pathSegment(${argument.access})")
                }
            }
            start = match.range.last + 1
        }
        static.append(url.path.substring(start))

        val query = url.queryParameters.map { parameter ->
            parameter to (parameter.value as? UrlProperty)
                ?.let { urlArgument(it, parameter.name, scope, location, name, path = false) }
        }
        if (query.any { (_, argument) -> argument?.nullable == true }) {
            // An optional parameter decides at render time whether the ones after it start
            // with '?' or '&', so the URL is assembled instead of concatenated.
            flush()
            val builder = "url${generatedVariable++}"
            code.statement("var $builder = new StringBuilder();")
            pieces.forEach { code.statement("$builder.append($it);") }
            query.forEach { (parameter, argument) ->
                val value = argument?.access ?: "\"${javaString((parameter.value as UrlLiteral).value)}\""
                code.statement(
                    "no.beint.thim.UrlEncoding.appendQuery($builder, \"${javaString(parameter.name)}\", $value);",
                )
            }
            return "$builder.toString()"
        }
        query.forEachIndexed { index, (parameter, argument) ->
            static.append(if (index == 0) '?' else '&').append(parameter.name).append('=')
            if (argument == null) {
                static.append(encodeUrl((parameter.value as UrlLiteral).value))
            } else {
                flush()
                pieces.add("no.beint.thim.UrlEncoding.query(${argument.access})")
            }
        }
        flush()
        return pieces.joinToString(" + ").ifEmpty { "\"\"" }
    }

    private data class UrlArgument(val access: String, val nullable: Boolean)

    private fun urlArgument(
        value: UrlProperty,
        parameterName: String,
        scope: Scope,
        location: SourceLocation?,
        name: String,
        path: Boolean,
    ): UrlArgument {
        val resolved = scope.resolve(
            value.path,
            diagnosticContext(location, "THIM-PROPERTY-UNKNOWN", "th:$name"),
            location,
        )
        requireDiagnostic(path.not() || !resolved.nullable, "THIM-URL-ARGUMENT-NULLABLE", location) {
            "URL path variable '$parameterName' cannot be nullable"
        }
        val typeName = resolved.type.declaration.qualifiedName?.asString()
        requireDiagnostic(typeName !in setOf("no.beint.thim.SafeHtml", "no.beint.thim.TrustedUrl"), "THIM-URL-ARGUMENT-TYPE", location) {
            "URL parameter '$parameterName' cannot be a ${typeName?.substringAfterLast('.')}"
        }
        // Enum values render through their stable name, not a possibly overridden toString().
        val access = when {
            !resolved.type.isEnum() -> resolved.code
            resolved.nullable -> "(${resolved.code} == null ? null : ${resolved.code}.name())"
            else -> "${resolved.code}.name()"
        }
        return UrlArgument(access, resolved.nullable)
    }

    private fun checkRoute(url: UrlExpression, name: String, element: ElementNode, scope: Scope, location: SourceLocation?) {
        val method = when {
            name == "href" -> "GET"
            name == "action" -> formMethod(element).uppercase()
            name in htmxMethodAttributes -> htmxMethodAttributes.getValue(name)
            else -> return
        }
        routes.check(url.path, method, location, "th:$name", enumPathVariables(url, scope, location, name))
    }

    /** Path variables bound to an enum property, mapped to the enum's constant names. */
    private fun enumPathVariables(
        url: UrlExpression,
        scope: Scope,
        location: SourceLocation?,
        name: String,
    ): Map<String, List<String>> = url.pathVariables.mapNotNull { variable ->
        val property = url.parameter(variable).value as? UrlProperty ?: return@mapNotNull null
        val resolved = scope.resolve(
            property.path,
            diagnosticContext(location, "THIM-PROPERTY-UNKNOWN", "th:$name"),
            location,
        )
        val declaration = resolved.type.declaration as? KSClassDeclaration ?: return@mapNotNull null
        if (declaration.classKind != ClassKind.ENUM_CLASS) return@mapNotNull null
        variable to declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()
    }.toMap()

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

    private fun staticAttributeValue(value: String): String =
        // Attribute entities remain source-encoded; only escape the delimiter introduced by normalized output.
        value.replace("\"", "&quot;")

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

    private fun Binding.castType(): String = javaType(type, nullable)

    private fun javaType(type: KSType, boxed: Boolean = false): String {
        val name = type.declaration.qualifiedName?.asString() ?: return "java.lang.Object"
        val primitive = when (name) {
            "kotlin.Boolean" -> "boolean" to "java.lang.Boolean"
            "kotlin.Byte" -> "byte" to "java.lang.Byte"
            "kotlin.Short" -> "short" to "java.lang.Short"
            "kotlin.Int" -> "int" to "java.lang.Integer"
            "kotlin.Long" -> "long" to "java.lang.Long"
            "kotlin.Char" -> "char" to "java.lang.Character"
            "kotlin.Float" -> "float" to "java.lang.Float"
            "kotlin.Double" -> "double" to "java.lang.Double"
            else -> null
        }
        if (primitive != null) return if (boxed || type.nullability == Nullability.NULLABLE) primitive.second else primitive.first
        val primitiveArray = when (name) {
            "kotlin.BooleanArray" -> "boolean[]"
            "kotlin.ByteArray" -> "byte[]"
            "kotlin.ShortArray" -> "short[]"
            "kotlin.IntArray" -> "int[]"
            "kotlin.LongArray" -> "long[]"
            "kotlin.CharArray" -> "char[]"
            "kotlin.FloatArray" -> "float[]"
            "kotlin.DoubleArray" -> "double[]"
            else -> null
        }
        if (primitiveArray != null) return primitiveArray
        if (name == "kotlin.Array") {
            val element = type.arguments.firstOrNull()?.type?.resolve()
            return (element?.let { javaType(it) } ?: "java.lang.Object") + "[]"
        }
        val javaName = when (name) {
            "kotlin.String" -> "java.lang.String"
            "kotlin.Any" -> "java.lang.Object"
            "kotlin.collections.Iterable" -> "java.lang.Iterable"
            "kotlin.collections.Collection", "kotlin.collections.MutableCollection" -> "java.util.Collection"
            "kotlin.collections.List", "kotlin.collections.MutableList" -> "java.util.List"
            "kotlin.collections.Set", "kotlin.collections.MutableSet" -> "java.util.Set"
            "kotlin.collections.Map", "kotlin.collections.MutableMap" -> "java.util.Map"
            else -> name
        }
        val arguments = type.arguments.mapNotNull { it.type?.resolve() }
        return if (arguments.isEmpty()) javaName else arguments.joinToString(", ", "$javaName<", ">") { javaType(it, boxed = true) }
    }

    private data class ResolvedPath(val code: String, val type: KSType, val nullable: Boolean)

    private data class Property(val type: KSType, val accessor: String)

    private data class RenderHelper(
        val name: String,
        val nodes: List<Node>,
        val scope: Scope,
        val context: String,
        val captures: List<Binding>,
    )

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

        fun capturedBindings(): List<Binding> = (bindings.values + listOfNotNull(selection, select)).distinctBy(Binding::code)

        fun selectValue(): Binding? = select

        fun errorsProperty(): ResolvedPath? {
            val property = model.property("errors") ?: return null
            val typeName = property.type.declaration.qualifiedName?.asString()
            if (typeName != "no.beint.thim.FormErrors" || property.type.nullability == Nullability.NULLABLE) return null
            return ResolvedPath("model.${property.accessor}()", property.type, false)
        }

        fun recordRootProperty(name: String) {
            recordUse(name)
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
                val name = parameter.name?.asString() ?: return@forEach
                if (getDeclaredFunctions().any { it.simpleName.asString() == name && it.parameters.isEmpty() }) {
                    add(name)
                }
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
        const val MAX_RENDER_PART_WEIGHT = 1200

        fun Node.renderWeight(): Int = when (this) {
            is RawNode -> 0
            is ElementNode -> 1 + attributes.values.sumOf { expression ->
                when {
                    expression == null -> 0
                    expression.trim().startsWith("#{") -> 12
                    expression.trim().startsWith("@{") -> 8
                    else -> 4
                }
            } + children.sumOf { it.renderWeight() }
        }

        val eachPattern = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(\\$\\{.+})")
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
            "th:attr", "th:case", "th:classappend", "th:inline", "th:insert",
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
        val integralMessageTypes = setOf(
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "int", "long", "short", "byte",
        )
        val messageTextTypes = stringTypes + numericTypes + booleanFieldTypes

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

private fun KSType.isEnum(): Boolean =
    (declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
