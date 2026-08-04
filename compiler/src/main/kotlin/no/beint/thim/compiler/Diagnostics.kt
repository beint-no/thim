package no.beint.thim.compiler

internal data class SourceLocation(
    val template: String,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$template.html:$line:$column"
}

internal class ThimDiagnostic(
    val code: String,
    val location: SourceLocation?,
    message: String,
) : IllegalArgumentException(buildString {
    if (location != null) append(location).append(' ')
    append(code).append(' ').append(message)
})

internal fun diagnostic(code: String, location: SourceLocation?, message: String): Nothing {
    throw ThimDiagnostic(code, location, message)
}

internal inline fun requireDiagnostic(
    value: Boolean,
    code: String,
    location: SourceLocation?,
    message: () -> String,
) {
    if (!value) diagnostic(code, location, message())
}
