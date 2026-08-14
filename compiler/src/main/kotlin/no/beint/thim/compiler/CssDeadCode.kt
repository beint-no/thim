package no.beint.thim.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class CssDeadCodeReport(
    val defined: Int,
    val used: List<String>,
    val prefixUsed: List<String>,
    val unused: List<String>,
    val unknownPrefixedTokens: List<String>,
) {
    fun json(): String = buildString {
        appendLine("{")
        appendLine("""  "defined": $defined,""")
        appendLine("""  "usedCount": ${used.size},""")
        appendLine("""  "prefixUsedCount": ${prefixUsed.size},""")
        appendLine("""  "unusedCount": ${unused.size},""")
        appendLine("""  "unknownPrefixedTokenCount": ${unknownPrefixedTokens.size},""")
        appendLine("""  "unused": ${jsonArray(unused)},""")
        appendLine("""  "prefixUsed": ${jsonArray(prefixUsed)},""")
        appendLine("""  "unknownPrefixedTokens": ${jsonArray(unknownPrefixedTokens)}""")
        appendLine("}")
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ") { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}

internal object CssDeadCode {
    fun analyze(cssRoot: Path?, usageRoots: List<Path>): CssDeadCodeReport {
        val catalog = if (cssRoot == null) CssCatalog(emptyMap()) else CssCatalog.load(cssRoot)
        val usage = CssUsage.scan(usageRoots)
        val used = linkedSetOf<String>()
        val prefixUsed = linkedSetOf<String>()
        catalog.names.forEach { name ->
            if (name in usage.tokens || name in runtimeAppliedClasses) {
                used += name
            } else if (usage.prefixes.any { prefix -> name.startsWith(prefix) }) {
                prefixUsed += name
            }
        }
        val unused = (catalog.names - used - prefixUsed).sorted()
        val unknown = usage.tokens
            .filter { token -> looksLikeOwnedClass(token) && token !in catalog.names }
            .sorted()
        return CssDeadCodeReport(
            defined = catalog.names.size,
            used = used.sorted(),
            prefixUsed = prefixUsed.sorted(),
            unused = unused,
            unknownPrefixedTokens = unknown,
        )
    }

    fun write(report: CssDeadCodeReport, file: Path) {
        Files.createDirectories(file.parent)
        Files.writeString(file, report.json(), StandardCharsets.UTF_8)
    }

    private fun looksLikeOwnedClass(token: String): Boolean =
        token.startsWith("r-") || token.startsWith("thim-")

    private val runtimeAppliedClasses = setOf("htmx-request", "htmx-indicator")
}

internal object CssDeadCodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var css: Path? = null
        val usage = mutableListOf<Path>()
        var out: Path? = null
        args.forEach { argument ->
            when {
                argument.startsWith("--css=") -> css = Path.of(argument.removePrefix("--css="))
                argument.startsWith("--usage=") -> usage.add(Path.of(argument.removePrefix("--usage=")))
                argument.startsWith("--out=") -> out = Path.of(argument.removePrefix("--out="))
            }
        }
        require(usage.isNotEmpty()) { "Usage: --css=<dir> --usage=<dir> [--usage=<dir>...] [--out=<file>]" }
        val report = CssDeadCode.analyze(css, usage)
        val rendered = report.json()
        out?.let { CssDeadCode.write(report, it) }
        print(rendered)
        if (report.unused.isNotEmpty()) {
            System.err.println("THIM-CSS-UNUSED ${report.unused.size} first-party CSS classes were never referenced")
        }
    }
}
