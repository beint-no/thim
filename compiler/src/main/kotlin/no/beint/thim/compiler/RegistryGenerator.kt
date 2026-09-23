package no.beint.thim.compiler

/** One compiled page model: its class, the generated renderer that serves it, and its request-data use. */
internal data class RegistryEntry(
    val modelName: String,
    val rendererReference: String,
    val usesRequestDataValues: Boolean,
)

/** Generates the module's [no.beint.thim.TemplateSet], which dispatches page models to their renderers. */
internal class RegistryGenerator(private val entries: List<RegistryEntry>) {
    fun generate(packageName: String, className: String): String = buildString {
        val chunks = entries.withIndex().chunked(DISPATCH_CHUNK)
        appendLine("package $packageName;")
        appendLine()
        appendLine("import java.io.IOException;")
        appendLine("import no.beint.thim.HtmlOutput;")
        appendLine("import no.beint.thim.RenderContext;")
        appendLine("import no.beint.thim.TemplateSet;")
        appendLine()
        appendLine("public final class $className implements TemplateSet {")
        appendLine("    // Exact page-model classes resolve in constant time; the instanceof chain in renderSubtype")
        appendLine("    // only serves subclasses of open models, matching the previous linear dispatch.")
        appendLine("    private static final java.util.Map<Class<?>, Integer> INDEX = index();")
        appendLine()
        appendLine("    // Built imperatively: javac's inference over one Map.ofEntries call with hundreds of")
        appendLine("    // distinct Class arguments took several seconds for a large application.")
        appendLine("    private static java.util.Map<Class<?>, Integer> index() {")
        appendLine("        var index = new java.util.HashMap<Class<?>, Integer>(${entries.size * 2});")
        entries.forEachIndexed { index, template ->
            appendLine("        index.put(${template.modelName}.class, $index);")
        }
        appendLine("        return java.util.Map.copyOf(index);")
        appendLine("    }")
        appendLine()
        appendLine("    private static final boolean[] REQUEST_DATA_VALUES = {")
        appendLine("        " + entries.joinToString(", ") { it.usesRequestDataValues.toString() })
        appendLine("    };")
        appendLine()
        appendLine("    @Override")
        appendLine("    public boolean supports(Class<?> modelType) {")
        appendLine("        return INDEX.containsKey(modelType);")
        appendLine("    }")
        appendLine()
        appendLine("    @Override")
        appendLine("    public boolean supportsReturnType(Class<?> returnType) {")
        appendLine("        // Spring supplies the runtime type when a value exists; Object chiefly represents a null return.")
        appendLine("        if (returnType == Object.class) {")
        appendLine("            return false;")
        appendLine("        }")
        appendLine("        return INDEX.containsKey(returnType) || isSupertypeOfModel(returnType);")
        appendLine("    }")
        appendLine()
        appendLine("    private static boolean isSupertypeOfModel(Class<?> returnType) {")
        appendLine("        return " + entries.joinToString(" ||\n            ") {
            "returnType.isAssignableFrom(${it.modelName}.class)"
        } + ";")
        appendLine("    }")
        appendLine()
        appendLine("    @Override")
        appendLine("    public boolean usesRequestDataValues(Class<?> modelType) {")
        appendLine("        var index = INDEX.get(modelType);")
        appendLine("        return index != null && REQUEST_DATA_VALUES[index];")
        appendLine("    }")
        appendLine()
        appendLine("    // Dispatch is split so every method stays far below HotSpot's 8000-byte limit for JIT")
        appendLine("    // compilation; one switch over every template left large applications interpreted.")
        appendLine("    @Override")
        appendLine("    public void render(Object model, RenderContext context, HtmlOutput output) throws IOException {")
        appendLine("        var index = INDEX.get(model.getClass());")
        appendLine("        if (index == null) {")
        appendLine("            renderSubtype(model, context, output);")
        appendLine("            return;")
        appendLine("        }")
        appendLine("        switch (index / $DISPATCH_CHUNK) {")
        chunks.indices.forEach { chunk ->
            appendLine("            case $chunk -> render$chunk(index, model, context, output);")
        }
        appendLine("            default -> throw new IllegalStateException(\"Unknown template index \" + index);")
        appendLine("        }")
        appendLine("    }")
        chunks.forEachIndexed { chunk, templates ->
            appendLine()
            appendLine("    private static void render$chunk(int index, Object model, RenderContext context, HtmlOutput output) throws IOException {")
            appendLine("        switch (index) {")
            templates.forEach { (index, template) ->
                appendLine("            case $index -> ${template.rendererReference}.render((${template.modelName}) model, context, output);")
            }
            appendLine("            default -> throw new IllegalStateException(\"Unknown template index \" + index);")
            appendLine("        }")
            appendLine("    }")
        }
        appendLine()
        appendLine("    // Only instances of subclasses of open page models reach this chain.")
        appendLine("    private static void renderSubtype(Object model, RenderContext context, HtmlOutput output) throws IOException {")
        entries.forEach {
            appendLine("        if (model instanceof ${it.modelName} typed) {")
            appendLine("            ${it.rendererReference}.render(typed, context, output);")
            appendLine("            return;")
            appendLine("        }")
        }
        appendLine("        throw new IllegalArgumentException(\"No compiled template for \" + model.getClass().getName());")
        appendLine("    }")
        appendLine("}")
    }

    internal companion object {
        /** Templates per dispatch method: at most about 4 KB of bytecode, half HotSpot's JIT limit. */
        const val DISPATCH_CHUNK = 256
    }
}
