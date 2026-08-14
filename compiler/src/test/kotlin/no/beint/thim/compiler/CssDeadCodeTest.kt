package no.beint.thim.compiler

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CssDeadCodeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `parses escaped utility class names`() {
        val names = CssCatalog.classNames(
            """
            /* comment .r-ignored { } */
            .r-hidden { display: none }
            .r-h-\[80vh\] { height: 80vh }
            .r-max-\[768px\]\:r-w-fit { width: fit-content }
            .r-text-\(--wa-color-green-60\) { color: green }
            .r-foo.r-bar { }
            """.trimIndent(),
        )

        assertEquals(
            setOf(
                "r-hidden",
                "r-h-[80vh]",
                "r-max-[768px]:r-w-fit",
                "r-text-(--wa-color-green-60)",
                "r-foo",
                "r-bar",
            ),
            names,
        )
    }

    @Test
    fun `skips vendor stylesheets and unused classes are reported`() {
        write("css/base.css", ".r-used { }\n.r-dead { }\n.r-analytics-sparkline-bar-1 { }\n")
        write("css/vendor/lib.css", ".r-vendor-only { }\n")
        write("src/page.html", """<div class="r-used"></div>""")
        write("src/Page.kt", """val height = "r-analytics-sparkline-bar-${'$'}index"""")

        val report = CssDeadCode.analyze(directory.resolve("css"), listOf(directory.resolve("src")))

        assertEquals(listOf("r-dead"), report.unused)
        assertEquals(listOf("r-analytics-sparkline-bar-1"), report.prefixUsed)
        assertTrue("r-used" in report.used)
        assertTrue("r-vendor-only" !in report.unused)
        assertTrue("r-vendor-only" !in report.used)
    }

    @Test
    fun `reads class tokens from javascript and kotlin literals`() {
        write("css/base.css", ".r-hidden { }\n.r-nav-color--dashboard { }\n.r-missing-in-css { }\n")
        write("src/base.js", "el.classList.toggle('r-hidden', true);\n")
        write("src/Nav.kt", """val colorClass = "r-nav-color--dashboard"""")

        val report = CssDeadCode.analyze(directory.resolve("css"), listOf(directory.resolve("src")))

        assertEquals(listOf("r-missing-in-css"), report.unused)
        assertEquals(setOf("r-hidden", "r-nav-color--dashboard"), report.used.toSet())
    }

    @Test
    fun `short interpolations are not treated as prefixes`() {
        assertTrue(!CssUsage.isDynamicPrefix("r-h-"))
        assertTrue(CssUsage.isDynamicPrefix("r-is-"))
        assertTrue(CssUsage.isDynamicPrefix("r-mchart-"))
        assertTrue(CssUsage.isDynamicPrefix("r-analytics-sparkline-bar-"))
    }

    @Test
    fun `reads class tokens from javascript template literals`() {
        write(
            "css/base.css",
            ".r-message-row { }\n.r-is-google { }\n.r-message-row-in { }\n.r-message-row-out { }\n",
        )
        write(
            "src/app.js",
            "item.className = `r-message-row \${tone}`;\n" +
                "mark.className = `r-ads-provider-mark r-is-\${provider}`;\n" +
                "row.className = `r-message-row \${outbound ? \"r-message-row-out\" : \"r-message-row-in\"}`;\n",
        )

        val report = CssDeadCode.analyze(directory.resolve("css"), listOf(directory.resolve("src")))

        assertEquals(emptyList(), report.unused)
        assertTrue("r-message-row" in report.used)
        assertTrue("r-message-row-in" in report.used)
        assertEquals(listOf("r-is-google"), report.prefixUsed)
    }

    @Test
    fun `accepts commas inside utility class names`() {
        write("css/base.css", ".r-grid-cols-\\[minmax\\(0\\,1fr\\)_7rem\\] { }\n")
        write("src/page.html", """<div class="r-grid-cols-[minmax(0,1fr)_7rem]"></div>""")

        val report = CssDeadCode.analyze(directory.resolve("css"), listOf(directory.resolve("src")))

        assertEquals(emptyList(), report.unused)
        assertEquals(listOf("r-grid-cols-[minmax(0,1fr)_7rem]"), report.used)
    }

    @Test
    fun `keeps class tokens after a kotlin interpolation`() {
        val segments = CssUsage.interpolationSegments(
            "r-sticky r-z-10 \$border r-max-[576px]:r-min-w-[55px] \$left",
        )
        assertTrue(segments.any { it.contains("r-max-[576px]:r-min-w-[55px]") }, segments.toString())
    }

    private fun write(relative: String, contents: String) {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, contents)
    }
}
