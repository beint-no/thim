package no.beint.thim.compiler

/** Components are linked into the HTML tree; only their lexical scope survives until code generation. */
internal class ComponentCatalog(templates: Map<String, List<Node>>) {
    private data class Definition(val template: String, val element: ElementNode, val slots: Map<String, ElementNode>)

    private val definitions = linkedMapOf<String, Definition>()
    private val used = mutableSetOf<String>()

    init {
        templates.forEach { (template, nodes) ->
            nodes.asSequence().flatMap(Node::elements).forEach { element ->
                if ("th:component" in element.attributes) {
                    val name = element.attributes["th:component"].orEmpty()
                    check(name.matches(componentName) && !name.startsWith("th:"), element, "NAME", "invalid component name '$name'")
                    check(name !in definitions, element, "DUPLICATE", "component '$name' is already defined")
                    check("th:fragment" !in element.attributes, element, "DEFINITION", "a component cannot also be a fragment")
                    val type = element.attributes["th:props"]
                    check("th:props" !in element.attributes || type != null && type.matches(typeName), element, "PROPS", "th:props must name a fully qualified props class")
                    val slots = linkedMapOf<String, ElementNode>()
                    element.children.asSequence().flatMap(Node::elements).forEach { child ->
                        check("th:component" !in child.attributes, child, "DEFINITION", "component definitions cannot be nested")
                        if ("th:slot" in child.attributes) {
                            val slot = child.attributes["th:slot"].orEmpty()
                            check(child.name == "th:block" && child.attributes.keys.all { it in setOf("th:slot", "required") }, child, "SLOT", "slots use <th:block th:slot=\"name\"> with optional required")
                            check(slot.matches(slotName) && slot !in slots, child, "SLOT", "invalid or duplicate slot '$slot'")
                            check(!("required" in child.attributes && child.children.any(::meaningful)), child, "SLOT", "a required slot cannot have fallback content")
                            check(child.children.asSequence().flatMap(Node::elements).none { "th:slot" in it.attributes }, child, "SLOT", "slot declarations cannot be nested")
                            slots[slot] = child
                        }
                    }
                    definitions[name] = Definition(template, element, slots)
                }
            }
        }
    }

    fun unused(): List<String> = definitions.keys.filterNot(used::contains)

    fun expand(
        call: ElementNode,
        stack: Set<String>,
        expandCaller: (List<Node>) -> List<Node>,
        expandDefinition: (String, List<Node>, Set<String>) -> List<Node>,
    ): ElementNode {
        val name = call.name
        val definition = definitions[name]
            ?: diagnostic("THIM-COMPONENT-UNKNOWN", call.location, "component '$name' does not exist")
        check(name !in stack, call, "RECURSIVE", "recursive composition: ${(stack + name).joinToString(" -> ")}")
        check(call.attributes.keys.all { it in setOf("th:props", "th:if", "th:unless", "th:each") }, call, "ATTRIBUTE", "component calls accept th:props, th:if, th:unless and th:each; put HTML attributes on the definition or in typed props")
        val type = definition.element.attributes["th:props"]
        val expression = call.attributes["th:props"]
        check((type == null) == (expression == null) && (expression != null || "th:props" !in call.attributes), call, "PROPS", if (type == null) "'$name' takes no props" else "'$name' requires th:props with a non-null $type")
        if (expression != null) Expressions.path(expression, "${call.location} THIM-COMPONENT-PROPS <$name>")
        val supplied = linkedMapOf<String, List<Node>>()
        val body = mutableListOf<Node>()
        call.children.forEach { child ->
            if (child is ElementNode && "th:fill" in child.attributes) {
                val slot = child.attributes["th:fill"].orEmpty()
                check(child.name == "th:block" && child.attributes.size == 1, child, "FILL", "named content uses <th:block th:fill=\"name\">")
                check(slot != "default" && slot in definition.slots && slot !in supplied, child, "FILL", "unknown or duplicate named slot '$slot' on '$name'")
                supplied[slot] = expandCaller(child.children)
            } else body += child
        }
        if (body.any(::meaningful)) {
            check("default" in definition.slots, call, "FILL", "'$name' has no default slot")
            supplied["default"] = expandCaller(body)
        }
        definition.slots.forEach { (slot, node) ->
            check("required" !in node.attributes || slot in supplied, call, "SLOT-REQUIRED", "'$name' requires slot '$slot' declared at ${node.location}")
        }
        fun fill(node: Node): Node = when (node) {
            is RawNode -> node
            is ElementNode -> {
                val slot = node.attributes["th:slot"]
                if (slot != null) {
                    val content = supplied[slot]
                    ElementNode("th:block", linkedMapOf(), node.location, emptyMap(),
                        (content ?: node.children.map(::fill)).toMutableList(), callerContent = content != null)
                } else node.copy(children = node.children.map(::fill).toMutableList())
            }
        }
        val attributes = LinkedHashMap(definition.element.attributes)
        attributes.remove("th:component")
        attributes.remove("th:props")
        val root = definition.element.copy(attributes = attributes, children = definition.element.children.map(::fill).toMutableList())
        used += name
        return ElementNode("th:block", LinkedHashMap(call.attributes.filterKeys { it != "th:props" }), call.location,
            call.attributeLocations, expandDefinition(definition.template, listOf(root), stack + name).toMutableList(),
            component = ComponentBinding(name, type, expression))
    }

    private fun check(valid: Boolean, node: ElementNode, code: String, message: String) {
        requireDiagnostic(valid, "THIM-COMPONENT-$code", node.location) { message }
    }

    private companion object {
        val componentName = Regex("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*")
        val typeName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
        val slotName = Regex("[a-z][a-z0-9-]*")
        fun meaningful(node: Node): Boolean = node !is RawNode || node.value.isNotBlank()
    }
}
