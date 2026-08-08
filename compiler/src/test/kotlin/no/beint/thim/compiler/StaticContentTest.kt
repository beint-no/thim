package no.beint.thim.compiler

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StaticContentTest {
    @Test
    fun `reuses the byte range for identical static content`() {
        val content = StaticContent()

        val first = content.append("Save")
        val second = content.append("Cancel")
        val repeated = content.append("Save")

        assertEquals(first, repeated)
        assertEquals(0 until 4, first)
        assertEquals(4 until 10, second)
        assertContentEquals("SaveCancel".toByteArray(StandardCharsets.UTF_8), content.bytes())
    }
}
