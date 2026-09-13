package no.beint.thim.compiler

internal data class ComponentDefinition(
    val name: String,
    val template: String,
    val modelName: String?,
    val element: ElementNode,
    val slots: Map<String, ElementNode>,
)

internal class ComponentLinker(templates: Map<String, List<Node>>) {
    val definitions: Map<String, ComponentDefinition>
    private val used = mutableSetOf<String>()
    private var nextId = 0

    init {
        val found = linkedMapOf<String, ComponentDefinition>()
        templates.forEach { (template, nodes) ->
            nodes.asSequence().flatMap(Node::elements).forEach { element ->
                requireDiagnostic(element.attributes.keys.none { it in legacyAttributes }, "THIM-COMPOSITION-REMOVED", element.location) {
                    "th:fragment/th:replace/th:insert/th:include were removed; declare <ui:component> and invoke <ui:name> instead (see COMPONENTS.md)"
                }
            }
            val roots = nodes.filterNot { it is RawNode && (it.value.isBlank() || it.value.trim().startsWith("<!--")) }
            val declaration = roots.singleOrNull() as? ElementNode
            if (declaration?.name == "ui:component") {
                attributes(declaration, setOf("name", "props"))
                val name = declaration.attributes["name"]
                requireDiagnostic(name != null && name.matches(componentName) && name !in reserved, "THIM-COMPONENT-NAME", declaration.location) {
                    "component name must be lowercase kebab-case and cannot be reserved"
                }
                val slots = linkedMapOf<String, ElementNode>()
                declaration.children.asSequence().flatMap(Node::elements).filter { it.name == "ui:slot" }.forEach { slot ->
                    attributes(slot, setOf("name", "required"))
                    val slotName = slot.attributes["name"] ?: "default"
                    requireDiagnostic(slotName.matches(componentName), "THIM-SLOT-NAME", slot.location) { "invalid slot name '$slotName'" }
                    requireDiagnostic("required" !in slot.attributes || slot.attributes["required"] == null, "THIM-SLOT-REQUIRED", slot.location) { "required is a valueless attribute" }
                    requireDiagnostic("required" !in slot.attributes || slot.children.all { it is RawNode && it.value.isBlank() }, "THIM-SLOT-DEFAULT", slot.location) { "a required slot cannot have fallback content" }
                    requireDiagnostic(slots.put(slotName, slot) == null, "THIM-SLOT-DUPLICATE", slot.location) { "slot '$slotName' is declared twice" }
                }
                val modelName = declaration.attributes["props"]
                requireDiagnostic("props" !in declaration.attributes || modelName != null && modelName.matches(qualifiedName), "THIM-COMPONENT-MODEL", declaration.location) { "props must name a fully qualified Kotlin class or Java record" }
                val definition = ComponentDefinition(name!!, template, modelName, declaration, slots)
                requireDiagnostic(found.put(name, definition) == null, "THIM-COMPONENT-DUPLICATE", declaration.location) { "component '$name' is already declared" }
            } else {
                requireDiagnostic(nodes.asSequence().flatMap(Node::elements).none { it.name == "ui:component" }, "THIM-COMPONENT-ROOT", declaration?.location) { "$template: ui:component must be the only root of its file" }
            }
        }
        definitions = found
    }

    fun isComponent(template: String): Boolean = definitions.values.any { it.template == template }

    fun unusedComponents(): List<String> = definitions.keys.filterNot(used::contains)

    fun expand(nodes: List<Node>): List<Node> = expandNodes(nodes, emptyList(), null, emptyMap())

    fun validationNodes(definition: ComponentDefinition): List<Node> =
        expandNodes(definition.element.children, listOf(definition.name), null, emptyMap())

    private fun expandNodes(nodes: List<Node>, stack: List<String>, owner: Int?, slots: Map<String, List<Node>>): List<Node> =
        nodes.map { node ->
            if (node !is ElementNode) return@map node
            if (node.name == "ui:slot") {
                requireDiagnostic(stack.isNotEmpty(), "THIM-SLOT-SCOPE", node.location) { "ui:slot is only valid inside a component definition" }
                val name = node.attributes["name"] ?: "default"
                val supplied = slots[name]
                if (supplied != null) SlotNode(requireNotNull(owner), supplied)
                else ElementNode("th:block", linkedMapOf(), node.location, emptyMap(), expandNodes(node.children, stack, owner, slots).toMutableList())
            } else if (node.name.startsWith("ui:")) {
                val name = node.name.removePrefix("ui:")
                val definition = definitions[name]
                    ?: diagnostic("THIM-COMPONENT-UNKNOWN", node.location, "unknown component <${node.name}>")
                requireDiagnostic(name !in stack, "THIM-COMPONENT-RECURSIVE", node.location) { "recursive components: ${(stack + name).joinToString(" -> ")}" }
                attributes(node, setOf("props", "th:if", "th:unless", "th:each"))
                val props = node.attributes["props"]
                requireDiagnostic((props != null) == (definition.modelName != null), "THIM-COMPONENT-PROPS", node.location) { "<$name> ${if (definition.modelName == null) "does not accept props" else "requires props of type ${definition.modelName}"}" }
                val supplied = linkedMapOf<String, List<Node>>()
                val default = mutableListOf<Node>()
                node.children.forEach { child ->
                    if (child is ElementNode && child.name == "ui:fill") {
                        attributes(child, setOf("name"))
                        val slotName = child.attributes["name"] ?: diagnostic("THIM-SLOT-NAME", child.location, "ui:fill requires name")
                        requireDiagnostic(supplied.put(slotName, expandNodes(child.children, stack, owner, slots)) == null, "THIM-SLOT-DUPLICATE", child.location) { "slot '$slotName' supplied twice" }
                    } else default += child
                }
                if (default.any { it !is RawNode || it.value.isNotBlank() }) {
                    requireDiagnostic("default" !in supplied, "THIM-SLOT-DUPLICATE", node.location) { "default slot supplied twice" }
                    supplied["default"] = expandNodes(default, stack, owner, slots)
                }
                requireDiagnostic(supplied.keys.all { it in definition.slots }, "THIM-SLOT-UNKNOWN", node.location) { "<$name> accepts slots ${definition.slots.keys}; received ${supplied.keys}" }
                val missing = definition.slots.filter { (slotName, slot) -> "required" in slot.attributes && slotName !in supplied }.keys
                requireDiagnostic(missing.isEmpty(), "THIM-SLOT-MISSING", node.location) { "<$name> requires slots $missing" }
                used += name
                val id = nextId++
                val invocation = ComponentNode(id, definition, props?.let { Expressions.path(it, "${node.location} component props") }, node.location,
                    expandNodes(definition.element.children, stack + name, id, supplied))
                val controls = LinkedHashMap(node.attributes.filterKeys { it.startsWith("th:") })
                if (controls.isEmpty()) invocation else ElementNode("th:block", controls, node.location, node.attributeLocations, mutableListOf(invocation))
            } else {
                node.copy(children = expandNodes(node.children, stack, owner, slots).toMutableList())
            }
        }

    private fun attributes(element: ElementNode, allowed: Set<String>) {
        requireDiagnostic(element.attributes.all { (name, value) -> name == "required" || value != null }, "THIM-COMPONENT-ATTRIBUTE", element.location) {
            "component attributes require quoted values (except required)"
        }
        requireDiagnostic(element.attributes.keys.all { it in allowed }, "THIM-COMPONENT-ATTRIBUTE", element.location) {
            "<${element.name}> accepts only $allowed; HTML attributes must be explicit properties of the component"
        }
    }

    private companion object {
        val componentName = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
        val qualifiedName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
        val reserved = setOf("component", "slot", "fill")
        val legacyAttributes = setOf("th:fragment", "th:replace", "th:insert", "th:include")
    }
}
