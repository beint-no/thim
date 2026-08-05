package no.beint.thim.compiler

/**
 * Validates cross-references in an expanded page. Runs only on complete documents (root
 * <html>), where a missing id is provable; partial templates may reference elements in an
 * existing client document. Runs after renderer compilation, so th:field expressions are
 * already validated and their generated ids can be derived syntactically. Ids declared
 * under th:if, th:unless, or th:each count as declared for references but never as
 * provable duplicates; a static id repeated by a loop is only likely to duplicate, so it
 * warns instead of failing.
 */
internal class DocumentChecker(private val warn: (String) -> Unit) {
    private data class DeclaredId(val location: SourceLocation, val conditional: Boolean)

    fun check(nodes: List<Node>) {
        val root = nodes.firstOrNull { it is ElementNode } as? ElementNode ?: return
        if (root.name != "html") return
        val ids = linkedMapOf<String, MutableList<DeclaredId>>()
        nodes.forEach { collectIds(it, inLoop = false, conditional = false, ids) }
        ids.forEach { (id, declarations) ->
            val unconditional = declarations.filter { !it.conditional }
            requireDiagnostic(unconditional.size <= 1, "THIM-ID-DUPLICATE", unconditional.getOrNull(1)?.location) {
                "id \"$id\" is already declared at ${unconditional.first().location}"
            }
        }
        nodes.forEach { checkReferences(it, ids.keys) }
    }

    private fun collectIds(
        node: Node,
        inLoop: Boolean,
        conditional: Boolean,
        ids: MutableMap<String, MutableList<DeclaredId>>,
    ) {
        if (node !is ElementNode) return
        val loop = inLoop || "th:each" in node.attributes
        val branch = conditional || "th:if" in node.attributes || "th:unless" in node.attributes
        node.attributes["id"]?.takeIf(String::isNotEmpty)?.let { id ->
            val location = node.attributeLocations["id"] ?: node.location
            if (loop) {
                warn("$location THIM-EACH-STATIC-ID id \"$id\" inside th:each repeats every iteration; use data-* attributes or move the id outside the loop")
            }
            ids.getOrPut(id, ::mutableListOf).add(DeclaredId(location, branch || loop))
        }
        node.attributes["th:field"]?.let { field ->
            generatedFieldId(node, field)?.let { id ->
                val location = node.attributeLocations["th:field"] ?: node.location
                if (loop) {
                    warn("$location THIM-EACH-STATIC-ID th:field inside th:each generates id \"$id\" every iteration")
                }
                ids.getOrPut(id, ::mutableListOf).add(DeclaredId(location, branch || loop))
            }
        }
        node.children.forEach { collectIds(it, loop, branch, ids) }
    }

    private fun generatedFieldId(element: ElementNode, expression: String): String? {
        val fieldName = expression.trim().removePrefix("*{").removeSuffix("}")
        val radio = element.name == "input" && element.attributes["type"]?.trim()?.lowercase() == "radio"
        return if (radio) element.attributes["value"]?.let { "$fieldName.$it" } else fieldName
    }

    private fun checkReferences(node: Node, ids: Set<String>) {
        if (node !is ElementNode) return
        if (node.name == "label") checkTokens(node, "for", ids, list = false)
        ariaAttributes.forEach { checkTokens(node, it, ids, list = true) }
        node.attributes["href"]?.let { href ->
            if ("th:href" !in node.attributes && href.startsWith("#")) {
                val target = href.substring(1)
                requireDiagnostic(
                    target.isEmpty() || target == "top" || target in ids,
                    "THIM-REFERENCE-UNKNOWN",
                    node.attributeLocations["href"] ?: node.location,
                ) {
                    "href=\"#$target\" has no matching id in this page"
                }
            }
        }
        node.children.forEach { checkReferences(it, ids) }
    }

    private fun checkTokens(node: ElementNode, attribute: String, ids: Set<String>, list: Boolean) {
        if ("th:$attribute" in node.attributes) return
        val value = node.attributes[attribute] ?: return
        val tokens = if (list) value.split(whitespace).filter(String::isNotEmpty) else listOf(value.trim())
        tokens.forEach { token ->
            requireDiagnostic(token in ids, "THIM-REFERENCE-UNKNOWN", node.attributeLocations[attribute] ?: node.location) {
                "$attribute=\"$token\" has no matching id in this page"
            }
        }
    }

    private companion object {
        val ariaAttributes = listOf("aria-labelledby", "aria-describedby", "aria-controls")
        val whitespace = Regex("\\s+")
    }
}
