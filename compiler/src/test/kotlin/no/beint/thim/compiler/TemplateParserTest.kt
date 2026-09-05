package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateParserTest {
    @Test
    fun `locations preserve lines and UTF-16 columns across multiline tags`() {
        val source = "<!--😀-->\r\n<section\n id=\"root\"\r\n class=\"panel\"><span title=\"æ\">Hi</span>\n</section>"
        val section = TemplateParser("multiline", source).parse().filterIsInstance<ElementNode>().single()
        val span = section.children.filterIsInstance<ElementNode>().single()

        fun expected(token: String): SourceLocation {
            val offset = source.indexOf(token)
            val prefix = source.substring(0, offset)
            return SourceLocation("multiline", prefix.count { it == '\n' } + 1, offset - prefix.lastIndexOf('\n'))
        }

        assertEquals(expected("section"), section.location)
        assertEquals(expected("id="), section.attributeLocations["id"])
        assertEquals(expected("class="), section.attributeLocations["class"])
        assertEquals(expected("span"), span.location)
        assertEquals(expected("title="), span.attributeLocations["title"])
        val firstLine = TemplateParser("inline", "<!--😀--><p id=\"p\"></p>").parse().filterIsInstance<ElementNode>().single()
        assertEquals(SourceLocation("inline", 1, 11), firstLine.location)
        assertEquals(SourceLocation("inline", 1, 13), firstLine.attributeLocations["id"])
    }

    @Test
    fun `diagnostics retain locations on first and last lines`() {
        val duplicate = assertFailsWith<ThimDiagnostic> {
            TemplateParser("duplicate", "<div\n id=\"first\"\n id=\"second\"></div>\n").parse()
        }
        assertEquals(SourceLocation("duplicate", 3, 2), duplicate.location)
        assertEquals("THIM-HTML-DUPLICATE-ATTRIBUTE", duplicate.code)

        val unterminated = assertFailsWith<ThimDiagnostic> {
            TemplateParser("end", "<p>ok</p>\n<").parse()
        }
        assertEquals(SourceLocation("end", 2, 1), unterminated.location)
        assertEquals("THIM-HTML-UNTERMINATED", unterminated.code)
    }

    @Test
    fun `p auto-closes before another p`() {
        val paragraphs = TemplateParser("t", "<p>a<p>b</p>").parse().filterIsInstance<ElementNode>()

        assertEquals(listOf("p", "p"), paragraphs.map(ElementNode::name))
        assertTrue(paragraphs[0].children.none { it is ElementNode && it.name == "p" })
    }

    @Test
    fun `p auto-closes before a block element`() {
        val elements = TemplateParser("t", "<p>a<div>b</div>").parse().filterIsInstance<ElementNode>()

        assertEquals(listOf("p", "div"), elements.map(ElementNode::name))
    }

    @Test
    fun `explicit paragraph close stays two siblings`() {
        val paragraphs = TemplateParser("t", "<p>a</p><p>b</p>").parse().filterIsInstance<ElementNode>()

        assertEquals(2, paragraphs.size)
    }

    @Test
    fun `li still auto-closes before the next li`() {
        val items = TemplateParser("t", "<ul><li>a<li>b</ul>").parse()
            .filterIsInstance<ElementNode>()
            .single()
            .children
            .filterIsInstance<ElementNode>()

        assertEquals(listOf("li", "li"), items.map(ElementNode::name))
    }
}
