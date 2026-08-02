package no.beint.thim.compiler

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

public class ThimProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = ThimProcessor(environment)
}

private class ThimProcessor(
    environment: SymbolProcessorEnvironment,
) : SymbolProcessor {
    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger: KSPLogger = environment.logger
    private val templatesDirectory = requiredPath(environment, "thim.templates")
    private val messagesDirectory = requiredPath(environment, "thim.messages")
    private val generatedPackage = environment.options["thim.package"] ?: "thim.generated"
    private val registryName = environment.options["thim.registry"] ?: "ThimTemplates"
    private var completed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (completed) return emptyList()
        val models = resolver.getSymbolsWithAnnotation("no.beint.thim.Thim")
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (models.isEmpty()) return emptyList()

        try {
            validateConfiguration()
            val catalog = MessageCatalog.load(messagesDirectory)
            val generator = RendererGenerator(catalog)
            val templateNames = mutableSetOf<String>()
            val compiled = models.map { model ->
                val templateName = templateName(model)
                require(templateNames.add(templateName)) { "Template '$templateName' is assigned to more than one model" }
                val path = templatesDirectory.resolve("$templateName.html").normalize()
                require(path.startsWith(templatesDirectory.normalize())) { "Template name '$templateName' leaves the template directory" }
                require(Files.isRegularFile(path)) { "Template '$templateName' does not exist at $path" }
                val source = Files.readString(path, StandardCharsets.UTF_8)
                generator.compile(templateName, model, TemplateParser(templateName, source).parse())
            }
            generate(compiled)
            completed = true
        } catch (exception: IllegalArgumentException) {
            logger.error(exception.message ?: "Thim compilation failed")
            completed = true
        } catch (exception: IllegalStateException) {
            logger.error(exception.message ?: "Thim compilation failed")
            completed = true
        }
        return emptyList()
    }

    private fun generate(compiled: List<CompiledTemplate>) {
        val files = compiled.mapNotNull { it.model.containingFile }.distinct().toTypedArray()
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *files),
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "java",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.appendLine("package $generatedPackage;")
            output.appendLine()
            output.appendLine("import java.io.IOException;")
            output.appendLine("import no.beint.thim.Html;")
            output.appendLine("import no.beint.thim.RenderContext;")
            output.appendLine("import no.beint.thim.TemplateSet;")
            output.appendLine()
            compiled.forEach { output.append(it.source).appendLine() }
            output.appendLine("public final class $registryName implements TemplateSet {")
            output.appendLine("    @Override")
            output.appendLine("    public boolean supports(Class<?> modelType) {")
            output.appendLine("        return " + compiled.joinToString(" ||\n            ") {
                "modelType == ${it.model.qualifiedName!!.asString()}.class"
            } + ";")
            output.appendLine("    }")
            output.appendLine()
            output.appendLine("    @Override")
            output.appendLine("    public void render(Object model, RenderContext context, Appendable output) throws IOException {")
            compiled.forEach {
                val modelName = it.model.qualifiedName!!.asString()
                output.appendLine("        if (model instanceof $modelName typed) {")
                output.appendLine("            ${it.rendererName}.render(typed, context, output);")
                output.appendLine("            return;")
                output.appendLine("        }")
            }
            output.appendLine("        throw new IllegalArgumentException(\"No compiled template for \" + model.getClass().getName());")
            output.appendLine("    }")
            output.appendLine("}")
        }
    }

    private fun templateName(model: KSClassDeclaration): String {
        val annotation = model.annotations.single { it.annotationType.resolve().declaration.qualifiedName?.asString() == "no.beint.thim.Thim" }
        return annotation.arguments.single { it.name?.asString() == "value" }.value as String
    }

    private fun validateConfiguration() {
        require(Files.isDirectory(templatesDirectory)) { "Template directory does not exist: $templatesDirectory" }
        require(generatedPackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
            "Invalid generated package '$generatedPackage'"
        }
        require(registryName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid registry name '$registryName'" }
    }

    private fun requiredPath(environment: SymbolProcessorEnvironment, key: String): Path =
        Path.of(requireNotNull(environment.options[key]) { "Missing KSP option '$key'" }).toAbsolutePath().normalize()
}
