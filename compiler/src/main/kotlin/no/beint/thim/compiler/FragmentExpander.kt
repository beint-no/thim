package no.beint.thim.compiler

internal class FragmentExpander(
    templates: Map<String, List<Node>>,
) {
    private val fragments = templates.mapValues { (template, nodes) ->
        nodes.asSequence()
            .flatMap(Node::elements)
            .mapNotNull { element ->
                element.attributes["th:fragment"]?.let { declaration ->
                    val (name, parameters) = parseCall(declaration, "$template th:fragment")
                    name to Fragment(template, parameters, element)
                }
            }
            .toMap()
    }

    fun expand(template: String, nodes: List<Node>): List<Node> =
        nodes.flatMap { expand(it, template, emptyMap(), emptySet()) }

    private fun expand(
        node: Node,
        template: String,
        bindings: Map<String, Binding>,
        stack: Set<String>,
    ): List<Node> = when (node) {
        is RawNode -> listOf(node)
        is ElementNode -> {
            val replacement = node.attributes["th:replace"]
            if (replacement == null) {
                val attributes = linkedMapOf<String, String?>()
                node.attributes.forEach { (name, value) ->
                    attributes[name] = value?.let { substitute(it, bindings) }
                }
                val children = node.children.flatMap { expand(it, template, bindings, stack) }.toMutableList()
                listOf(ElementNode(node.name, attributes, node.location, node.attributeLocations, children))
            } else {
                val reference = reference(substitute(replacement, bindings), template, bindings)
                expand(reference, bindings, stack)
            }
        }
    }

    private fun expand(reference: Reference, parentBindings: Map<String, Binding>, stack: Set<String>): List<Node> {
        val key = "${reference.template}::${reference.name}"
        require(key !in stack) { "Recursive fragment composition: ${(stack + key).joinToString(" -> ")}" }
        val fragment = fragments[reference.template]?.get(reference.name)
            ?: error("Fragment '$key' does not exist")
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
        variablePattern.matchEntire(value.trim())?.let { match ->
            val binding = bindings[match.groupValues[1]]
            if (binding is ValueBinding) return binding.value
        }
        var result = value
        bindings.forEach { (name, binding) ->
            if (binding is ValueBinding) {
                val replacement = binding.value
                    .takeIf { it.startsWith("\${") && it.endsWith('}') }
                    ?.substring(2, binding.value.length - 1)
                    ?: binding.value
                result = identifier(name).replace(result, replacement)
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

    private fun identifier(name: String) = Regex("(?<![A-Za-z0-9_.])${Regex.escape(name)}(?![A-Za-z0-9_])")

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
        val identifierPattern = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        val variablePattern = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
    }
}
