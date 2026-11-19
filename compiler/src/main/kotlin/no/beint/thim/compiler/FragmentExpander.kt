package no.beint.thim.compiler

internal class FragmentExpander(
    templates: Map<String, List<Node>>,
) {
    private val components = ComponentCatalog(templates)
    private val fragments = templates.mapValues { (template, nodes) ->
        val defined = linkedMapOf<String, Fragment>()
        nodes.asSequence().flatMap(Node::elements).forEach { element ->
            element.attributes["th:fragment"]?.let { declaration ->
                val (name, parameters) = parseCall(declaration, "$template th:fragment")
                require(defined.put(name, Fragment(template, parameters, element)) == null) {
                    "${element.location} THIM-FRAGMENT-DUPLICATE fragment '$name' is already defined"
                }
            }
        }
        defined
    }

    private val used = mutableSetOf<String>()
    private val identifiers = mutableMapOf<String, Regex>()

    fun expand(template: String, nodes: List<Node>): List<Node> =
        nodes.flatMap { expand(it, template, emptyMap(), emptySet()) }

    fun unusedFragments(): List<String> = fragments.flatMap { (template, defined) ->
        defined.keys.filterNot { "$template::$it" in used }.map { "$template :: $it" }
    }

    fun unusedComponents(): List<String> = components.unused()

    /**
     * Parameters of used fragments that never appear in an expression of the fragment
     * body. Checked syntactically on the definition, so a parameter shadowed by a loop
     * variable of the same name still counts as used; that inaccuracy only suppresses a
     * warning. Unused fragments are skipped to keep one finding per fragment.
     */
    fun unusedParameters(): List<String> = fragments.flatMap { (template, defined) ->
        defined.filterKeys { "$template::$it" in used }.flatMap { (name, fragment) ->
            fragment.parameters
                .filterNot { parameter -> uses(fragment.element, parameter) }
                .map { "$template :: $name($it)" }
        }
    }

    private fun uses(element: ElementNode, parameter: String): Boolean {
        val pattern = identifier(parameter)
        return element.elements().any { node ->
            node.attributes.any { (attribute, value) ->
                attribute != "th:fragment" && value != null && pattern.containsMatchIn(value)
            }
        }
    }

    private fun expand(
        node: Node,
        template: String,
        bindings: Map<String, Binding>,
        stack: Set<String>,
    ): List<Node> {
        if (node is RawNode) return listOf(node)
        node as ElementNode
        if (node.callerContent) return listOf(node)
        if (':' in node.name && !node.name.startsWith("th:")) {
            val attributes = LinkedHashMap(node.attributes.mapValues { (_, value) -> value?.let { substitute(it, bindings) } })
            return listOf(components.expand(
                node.copy(attributes = attributes), stack,
                expandCaller = { content -> content.flatMap { expand(it, template, bindings, stack) } },
                expandDefinition = { origin, content, nested -> content.flatMap { expand(it, origin, emptyMap(), nested) } },
            ))
        }
        requireDiagnostic(
            node.attributes.keys.none { it in componentDirectives },
            "THIM-COMPONENT-CONTEXT", node.location,
        ) { "component directives must belong to a component definition, call, or slot" }
        val replacement = node.attributes["th:replace"]
        if (replacement != null) {
            val unexpected = node.attributes.keys.filter { it.startsWith("th:") && it !in replaceDirectives }
            requireDiagnostic(unexpected.isEmpty(), "THIM-REPLACE-ATTRIBUTE", node.location) {
                "th:replace only combines with th:if, th:unless, and th:each; remove $unexpected"
            }
            val control = LinkedHashMap<String, String?>()
            val controlLocations = linkedMapOf<String, SourceLocation>()
            var innerBindings = bindings
            if ("th:each" in node.attributes) {
                val each = node.attributes["th:each"]
                control["th:each"] = each?.let { substitute(it, bindings) }
                node.attributeLocations["th:each"]?.let { controlLocations["th:each"] = it }
                val loopVariable = each?.substringBefore(':', "")?.trim().orEmpty()
                if (loopVariable.isNotEmpty() && loopVariable in innerBindings) {
                    innerBindings = innerBindings - loopVariable
                }
            }
            for (directive in listOf("th:if", "th:unless")) {
                if (directive in node.attributes) {
                    control[directive] = node.attributes[directive]?.let { substitute(it, innerBindings) }
                    node.attributeLocations[directive]?.let { controlLocations[directive] = it }
                }
            }
            val expanded = expand(reference(substitute(replacement, innerBindings), template, innerBindings), innerBindings, stack)
            return if (control.isEmpty()) {
                expanded
            } else {
                listOf(ElementNode("th:block", control, node.location, controlLocations, expanded.toMutableList()))
            }
        }
        val attributes = LinkedHashMap(node.attributes.mapValues { (_, value) -> value?.let { substitute(it, bindings) } })
        val children = node.children.flatMap { expand(it, template, bindings, stack) }.toMutableList()
        return listOf(node.copy(attributes = attributes, children = children))
    }

    private fun expand(reference: Reference, parentBindings: Map<String, Binding>, stack: Set<String>): List<Node> {
        val key = "${reference.template}::${reference.name}"
        require(key !in stack) { "Recursive fragment composition: ${(stack + key).joinToString(" -> ")}" }
        val fragment = fragments[reference.template]?.get(reference.name)
            ?: error("Fragment '$key' does not exist")
        used += key
        val arguments = reference.arguments.mapValues { (_, value) -> binding(value, reference.origin, parentBindings) }
        val positional = reference.positional.map { value -> binding(value, reference.origin, parentBindings) }
        require(arguments.isEmpty() || positional.isEmpty()) { "$key: cannot mix named and positional arguments" }
        val localBindings = if (arguments.isNotEmpty()) {
            require(arguments.keys == fragment.parameters.toSet()) {
                "$key: expected arguments ${fragment.parameters}, received ${arguments.keys}"
            }
            arguments
        } else {
            require(positional.size == fragment.parameters.size) {
                "$key: expected ${fragment.parameters.size} arguments, received ${positional.size}"
            }
            fragment.parameters.zip(positional).toMap()
        }
        val attributes = LinkedHashMap(fragment.element.attributes)
        attributes.remove("th:fragment")
        val copy = ElementNode(
            fragment.element.name,
            attributes,
            fragment.element.location,
            fragment.element.attributeLocations,
            fragment.element.children.toMutableList(),
        )
        return expand(copy, fragment.template, parentBindings + localBindings, stack + key)
    }

    private fun binding(value: String, origin: String, bindings: Map<String, Binding>): Binding {
        val substituted = substitute(value, bindings).trim()
        return if (substituted.startsWith("~{")) {
            FragmentBinding(reference(substituted, origin, bindings))
        } else {
            ValueBinding(substituted)
        }
    }

    private fun reference(value: String, currentTemplate: String, bindings: Map<String, Binding>): Reference {
        val trimmed = value.trim()
        variablePattern.matchEntire(trimmed)?.let { match ->
            val bound = bindings[match.groupValues[1]]
            require(bound is FragmentBinding) { "$currentTemplate: '$trimmed' is not a fragment" }
            return bound.reference
        }
        require(trimmed.startsWith("~{") && trimmed.endsWith('}')) {
            "$currentTemplate: expected a ~{template :: fragment} expression, found '$value'"
        }
        val body = trimmed.substring(2, trimmed.length - 1).trim()
        val separator = body.indexOf("::")
        require(separator >= 0) { "$currentTemplate: fragment expression needs '::'" }
        val path = body.substring(0, separator).trim()
        val call = body.substring(separator + 2).trim()
        val (name, rawArguments) = parseCall(call, "$currentTemplate th:replace")
        val named = linkedMapOf<String, String>()
        val positional = mutableListOf<String>()
        rawArguments.forEach { argument ->
            val assignment = topLevelAssignment(argument)
            if (assignment == null) positional += argument else named[assignment.first] = assignment.second
        }
        return Reference(
            template = if (path.isEmpty()) currentTemplate else path,
            name = name,
            arguments = named,
            positional = positional,
            origin = currentTemplate,
        )
    }

    private fun substitute(value: String, bindings: Map<String, Binding>): String {
        if (bindings.isEmpty() || "\${" !in value) return value
        variablePattern.matchEntire(value.trim())?.let { match ->
            val binding = bindings[match.groupValues[1]]
            if (binding is ValueBinding) return binding.value
        }
        val result = StringBuilder()
        var index = 0
        while (true) {
            val start = value.indexOf("\${", index)
            val end = if (start == -1) -1 else value.indexOf('}', start + 2)
            if (end == -1) break
            result.append(value, index, start)
            result.append(substituteExpression(value.substring(start, end + 1), bindings))
            index = end + 1
        }
        result.append(value, index, value.length)
        return result.toString()
    }

    private fun substituteExpression(expression: String, bindings: Map<String, Binding>): String {
        var result = expression
        bindings.forEach { (name, binding) ->
            if (binding is ValueBinding) {
                val replacement = binding.value
                    .takeIf { it.startsWith("\${") && it.endsWith('}') }
                    ?.substring(2, binding.value.length - 1)
                    ?: binding.value
                result = identifier(name).replace(result, Regex.escapeReplacement(replacement))
            }
        }
        return result
    }

    private fun parseCall(value: String, context: String): Pair<String, List<String>> {
        val trimmed = value.trim()
        val opening = trimmed.indexOf('(')
        if (opening == -1) {
            require(trimmed.matches(identifierPattern)) { "$context: invalid fragment '$trimmed'" }
            return trimmed to emptyList()
        }
        require(trimmed.endsWith(')')) { "$context: invalid fragment call '$trimmed'" }
        val name = trimmed.substring(0, opening).trim()
        require(name.matches(identifierPattern)) { "$context: invalid fragment '$name'" }
        return name to splitTopLevel(trimmed.substring(opening + 1, trimmed.length - 1), context)
    }

    private fun splitTopLevel(value: String, context: String): List<String> {
        if (value.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var braces = 0
        var parentheses = 0
        var quote: Char? = null
        var start = 0
        value.forEachIndexed { index, character ->
            when {
                quote == null && character in charArrayOf('\'', '"') -> quote = character
                quote == character -> quote = null
                quote == null && character == '{' -> braces++
                quote == null && character == '}' -> braces--
                quote == null && character == '(' -> parentheses++
                quote == null && character == ')' -> parentheses--
                quote == null && character == ',' && braces == 0 && parentheses == 0 -> {
                    result += value.substring(start, index).trim()
                    start = index + 1
                }
            }
            require(braces >= 0 && parentheses >= 0) { "$context: unbalanced fragment arguments" }
        }
        require(quote == null && braces == 0 && parentheses == 0) { "$context: unbalanced fragment arguments" }
        result += value.substring(start).trim()
        return result
    }

    private fun topLevelAssignment(value: String): Pair<String, String>? {
        var braces = 0
        var quote: Char? = null
        value.forEachIndexed { index, character ->
            when {
                quote == null && character in charArrayOf('\'', '"') -> quote = character
                quote == character -> quote = null
                quote == null && character == '{' -> braces++
                quote == null && character == '}' -> braces--
                quote == null && braces == 0 && character == '=' -> {
                    val name = value.substring(0, index).trim()
                    require(name.matches(identifierPattern)) { "Invalid named fragment argument '$name'" }
                    return name to value.substring(index + 1).trim()
                }
            }
        }
        return null
    }

    private fun identifier(name: String) = identifiers.getOrPut(name) {
        Regex("(?<![A-Za-z0-9_.])${Regex.escape(name)}(?![A-Za-z0-9_])")
    }

    private data class Fragment(
        val template: String,
        val parameters: List<String>,
        val element: ElementNode,
    )

    private data class Reference(
        val template: String,
        val name: String,
        val arguments: Map<String, String>,
        val positional: List<String>,
        val origin: String,
    )

    private sealed interface Binding

    private data class ValueBinding(val value: String) : Binding

    private data class FragmentBinding(val reference: Reference) : Binding

    private companion object {
        val componentDirectives = setOf("th:slot", "th:fill", "th:props", "th:component")
        val replaceDirectives = setOf("th:replace", "th:if", "th:unless", "th:each", "th:fragment")
        val identifierPattern = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        val variablePattern = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
    }
}
