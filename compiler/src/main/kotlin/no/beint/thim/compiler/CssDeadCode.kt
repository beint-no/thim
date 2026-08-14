package no.beint.thim.compiler

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class CssCheckOptions(
    val ownedPrefixes: List<String> = defaultOwnedPrefixes,
    val allowedPrefixes: List<String> = emptyList(),
) {
    val owned: List<String> = normalizePrefixes(ownedPrefixes).ifEmpty { defaultOwnedPrefixes }
    val allowed: List<String> = normalizePrefixes(allowedPrefixes)

    fun isOwned(token: String): Boolean = owned.any { token.startsWith(it) }

    fun isAllowed(token: String): Boolean = allowed.isEmpty() || allowed.any { token.startsWith(it) }

    companion object {
        val defaultOwnedPrefixes = listOf("r-", "thim-")

        fun normalizePrefixes(prefixes: List<String>): List<String> =
            prefixes.map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

internal data class CssDeadCodeReport(
    val defined: Int,
    val used: List<String>,
    val prefixUsed: List<String>,
    val unused: List<String>,
    val unknown: List<String>,
    val disallowedPrefix: List<String>,
    val ownedPrefixes: List<String>,
    val allowedPrefixes: List<String>,
) {
    val unknownPrefixedTokens: List<String> get() = unknown

    fun json(): String = buildString {
        appendLine("{")
        appendLine("""  "defined": $defined,""")
        appendLine("""  "usedCount": ${used.size},""")
        appendLine("""  "prefixUsedCount": ${prefixUsed.size},""")
        appendLine("""  "unusedCount": ${unused.size},""")
        appendLine("""  "unknownCount": ${unknown.size},""")
        appendLine("""  "disallowedPrefixCount": ${disallowedPrefix.size},""")
        appendLine("""  "unused": ${jsonArray(unused)},""")
        appendLine("""  "unknown": ${jsonArray(unknown)},""")
        appendLine("""  "disallowedPrefix": ${jsonArray(disallowedPrefix)},""")
        appendLine("""  "prefixUsed": ${jsonArray(prefixUsed)},""")
        appendLine("""  "ownedPrefixes": ${jsonArray(ownedPrefixes)},""")
        appendLine("""  "allowedPrefixes": ${jsonArray(allowedPrefixes)}""")
        appendLine("}")
    }

    private fun jsonArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ", ") { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}

internal object CssDeadCode {
    fun analyze(
        cssRoot: Path?,
        usageRoots: List<Path>,
        options: CssCheckOptions = CssCheckOptions(),
    ): CssDeadCodeReport {
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
        val unknown = usage.htmlClassTokens
            .filter { token ->
                options.isOwned(token) &&
                    isClassToken(token) &&
                    token !in catalog.names &&
                    !CssUsage.isDynamicPrefix(token)
            }
            .sorted()
        val disallowed = if (options.allowed.isEmpty()) {
            emptyList()
        } else {
            usage.htmlClassTokens
                .filter { token -> isClassToken(token) && !options.isAllowed(token) }
                .sorted()
        }
        return CssDeadCodeReport(
            defined = catalog.names.size,
            used = used.sorted(),
            prefixUsed = prefixUsed.sorted(),
            unused = unused,
            unknown = unknown,
            disallowedPrefix = disallowed,
            ownedPrefixes = options.owned,
            allowedPrefixes = options.allowed,
        )
    }

    fun write(report: CssDeadCodeReport, file: Path) {
        Files.createDirectories(file.parent)
        Files.writeString(file, report.json(), StandardCharsets.UTF_8)
    }

    internal fun isClassToken(token: String): Boolean {
        if (token.isEmpty()) return false
        val start = token.first()
        if (!start.isLetter() && start != '_') return false
        if (',' in token || "::" in token) return false
        if (token.count { it == '[' } != token.count { it == ']' }) return false
        return true
    }

    private val runtimeAppliedClasses = setOf("htmx-request", "htmx-indicator")
}

internal object CssDeadCodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var css: Path? = null
        val usage = mutableListOf<Path>()
        var out: Path? = null
        var owned = CssCheckOptions.defaultOwnedPrefixes
        var allowed = emptyList<String>()
        args.forEach { argument ->
            when {
                argument.startsWith("--css=") -> css = Path.of(argument.removePrefix("--css="))
                argument.startsWith("--usage=") -> usage.add(Path.of(argument.removePrefix("--usage=")))
                argument.startsWith("--out=") -> out = Path.of(argument.removePrefix("--out="))
                argument.startsWith("--prefixes=") ->
                    owned = argument.removePrefix("--prefixes=").split(',').map(String::trim)
                argument.startsWith("--allowed=") ->
                    allowed = argument.removePrefix("--allowed=").split(',').map(String::trim)
            }
        }
        require(usage.isNotEmpty()) {
            "Usage: --css=<dir> --usage=<dir> [--usage=<dir>...] [--prefixes=r-,thim-] [--allowed=r-,wa-,htmx-] [--out=<file>]"
        }
        val report = CssDeadCode.analyze(css, usage, CssCheckOptions(owned, allowed))
        val rendered = report.json()
        out?.let { CssDeadCode.write(report, it) }
        print(rendered)
        if (report.unused.isNotEmpty()) {
            System.err.println("THIM-CSS-UNUSED ${report.unused.size} first-party CSS classes were never referenced")
        }
        if (report.unknown.isNotEmpty()) {
            System.err.println("THIM-CSS-UNKNOWN ${report.unknown.size} owned class tokens have no first-party CSS rule")
        }
        if (report.disallowedPrefix.isNotEmpty()) {
            System.err.println("THIM-CSS-PREFIX ${report.disallowedPrefix.size} class tokens are outside the allowed prefixes")
        }
    }
}
