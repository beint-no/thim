package no.beint.thim.compiler

internal sealed interface Node

internal data class RawNode(val value: String) : Node

internal data class ElementNode(
    val name: String,
    val attributes: LinkedHashMap<String, String?>,
    val children: MutableList<Node> = mutableListOf(),
    val selfClosing: Boolean = false,
) : Node

internal class TemplateParser(
    private val templateName: String,
    private val source: String,
) {
    private val roots = mutableListOf<Node>()
    private val stack = ArrayDeque<ElementNode>()
    private var index = 0

    fun parse(): List<Node> {
        while (index < source.length) {
            when {
                source.startsWith("<!--", index) -> rawUntil("-->")
                source[index] != '<' -> text()
                source.startsWith("</", index) -> closeElement()
                source.startsWith("<!", index) || source.startsWith("<?", index) -> declaration()
                else -> openElement()
            }
        }
        return roots
    }

    private fun currentChildren(): MutableList<Node> = stack.lastOrNull()?.children ?: roots

    private fun text() {
        val end = source.indexOf('<', index).let { if (it == -1) source.length else it }
        currentChildren().add(RawNode(source.substring(index, end)))
        index = end
    }

    private fun rawUntil(marker: String) {
        val end = source.indexOf(marker, index + marker.length)
        require(end >= 0) { "$templateName: unterminated $marker block" }
        val next = end + marker.length
        currentChildren().add(RawNode(source.substring(index, next)))
        index = next
    }

    private fun declaration() {
        val end = tagEnd(index)
        currentChildren().add(RawNode(source.substring(index, end + 1)))
        index = end + 1
    }

    private fun openElement() {
        val end = tagEnd(index)
        val inside = source.substring(index + 1, end)
        val selfClosing = inside.trimEnd().endsWith('/')
        val content = if (selfClosing) inside.trimEnd().dropLast(1) else inside
        val parsed = parseTag(content)
        while (stack.lastOrNull()?.name?.let { autoCloses(it, parsed.first) } == true) {
            stack.removeLast()
        }
        val element = ElementNode(parsed.first, parsed.second, selfClosing = selfClosing)
        currentChildren().add(element)
        index = end + 1

        if (element.name in voidElements || selfClosing) {
            return
        }
        if (element.name == "script" || element.name == "style") {
            val closing = "</${element.name}>"
            val close = source.indexOf(closing, index, ignoreCase = true)
            require(close >= 0) { "$templateName: unclosed <${element.name}> element" }
            element.children.add(RawNode(source.substring(index, close)))
            index = close + closing.length
            return
        }
        stack.addLast(element)
    }

    private fun closeElement() {
        val end = source.indexOf('>', index + 2)
        require(end >= 0) { "$templateName: unterminated closing element" }
        val name = source.substring(index + 2, end).trim().lowercase()
        while (stack.lastOrNull()?.let { it.name != name && it.name in optionalEndElements } == true) {
            stack.removeLast()
        }
        val open = stack.removeLastOrNull()
        require(open != null && open.name == name) {
            "$templateName: closing </$name> does not match <${open?.name ?: "none"}>"
        }
        index = end + 1
    }

    private fun tagEnd(start: Int): Int {
        var quote: Char? = null
        for (position in start + 1 until source.length) {
            val character = source[position]
            if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == character) {
                quote = null
            } else if (quote == null && character == '>') {
                return position
            }
        }
        error("$templateName: unterminated element")
    }

    private fun parseTag(content: String): Pair<String, LinkedHashMap<String, String?>> {
        var position = 0
        while (position < content.length && content[position].isWhitespace()) position++
        val nameStart = position
        while (position < content.length && !content[position].isWhitespace()) position++
        val name = content.substring(nameStart, position).lowercase()
        require(name.matches(namePattern)) { "$templateName: invalid element name '$name'" }

        val attributes = linkedMapOf<String, String?>()
        while (position < content.length) {
            while (position < content.length && content[position].isWhitespace()) position++
            if (position == content.length) break
            val attributeStart = position
            while (position < content.length && !content[position].isWhitespace() && content[position] != '=') position++
            val attributeName = content.substring(attributeStart, position)
            require(attributeName.matches(attributePattern)) { "$templateName: invalid attribute '$attributeName'" }
            require(attributeName !in attributes) { "$templateName: duplicate '$attributeName' attribute on <$name>" }
            while (position < content.length && content[position].isWhitespace()) position++
            var value: String? = null
            if (position < content.length && content[position] == '=') {
                position++
                while (position < content.length && content[position].isWhitespace()) position++
                require(position < content.length && content[position] in charArrayOf('"', '\'')) {
                    "$templateName: '$attributeName' must use a quoted value"
                }
                val quote = content[position++]
                val valueStart = position
                while (position < content.length && content[position] != quote) position++
                require(position < content.length) { "$templateName: unterminated '$attributeName' value" }
                value = content.substring(valueStart, position++)
            }
            attributes[attributeName] = value
        }
        return name to attributes
    }

    private companion object {
        fun autoCloses(open: String, next: String): Boolean = when (open) {
            "li" -> next == "li"
            "dt", "dd" -> next == "dt" || next == "dd"
            "rt", "rp" -> next == "rt" || next == "rp"
            "option" -> next == "option" || next == "optgroup"
            "optgroup" -> next == "optgroup"
            "thead" -> next == "tbody" || next == "tfoot"
            "tbody" -> next == "tbody" || next == "tfoot"
            "tr" -> next == "tr"
            "th", "td" -> next == "th" || next == "td"
            else -> false
        }

        val namePattern = Regex("[a-z][a-z0-9:._-]*")
        val attributePattern = Regex("[^\\s=<>]+")
        val optionalEndElements = setOf("li", "dt", "dd", "p", "rt", "rp", "optgroup", "option", "thead", "tbody", "tfoot", "tr", "th", "td")
        val voidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    }
}
