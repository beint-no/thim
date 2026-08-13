package no.beint.thim.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateParserTest {
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
