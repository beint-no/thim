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

private data class TemplateSource(
    val name: String,
    val modelName: String,
    val nodes: List<Node>,
)

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

        try {
            validateConfiguration()
            val templates = typedTemplates()
            if (templates.isEmpty()) {
                completed = true
                return emptyList()
            }
            val models = templates.associateWith { template ->
                requireNotNull(resolver.getClassDeclarationByName(resolver.getKSNameFromString(template.modelName))) {
                    "${template.name}: model '${template.modelName}' does not exist"
                }
            }
            require(models.values.distinctBy { it.qualifiedName?.asString() }.size == models.size) {
                "A page model can only be assigned to one template"
            }

            val catalog = MessageCatalog.load(messagesDirectory)
            val staticContent = StaticContent()
            val generator = RendererGenerator(catalog, staticContent, registryName)
            val compiled = models.map { (template, model) ->
                generator.compile(template.name, model, template.nodes)
            }
            generate(compiled, staticContent.bytes())
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

    private fun generate(compiled: List<CompiledTemplate>, staticContent: ByteArray) {
        val files = compiled.mapNotNull { it.model.containingFile }.distinct().toTypedArray()
        val dependencies = Dependencies(aggregating = true, *files)
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "bin",
        ).use { it.write(staticContent) }
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "java",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.appendLine("package $generatedPackage;")
            output.appendLine()
            output.appendLine("import java.io.IOException;")
            output.appendLine("import no.beint.thim.HtmlOutput;")
            output.appendLine("import no.beint.thim.RenderContext;")
            output.appendLine("import no.beint.thim.TemplateSet;")
            output.appendLine()
            compiled.forEach { output.append(it.source).appendLine() }
            output.appendLine("public final class $registryName implements TemplateSet {")
            output.appendLine("    static final byte[] STATIC = HtmlOutput.resource($registryName.class, \"$registryName.bin\");")
            output.appendLine()
            output.appendLine("    @Override")
            output.appendLine("    public boolean supports(Class<?> modelType) {")
            output.appendLine("        return " + compiled.joinToString(" ||\n            ") {
                "modelType == ${it.model.qualifiedName!!.asString()}.class"
            } + ";")
            output.appendLine("    }")
            output.appendLine()
            output.appendLine("    @Override")
            output.appendLine("    public void render(Object model, RenderContext context, HtmlOutput output) throws IOException {")
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
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = "META-INF/services/no.beint.thim.TemplateSet",
            extensionName = "",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.appendLine("$generatedPackage.$registryName")
        }
    }

    private fun typedTemplates(): List<TemplateSource> {
        val paths = mutableListOf<Path>()
        Files.walk(templatesDirectory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".html") }.forEach(paths::add)
        }
        return paths.sorted().mapNotNull { path ->
            val source = Files.readString(path, StandardCharsets.UTF_8)
            if (!source.contains("thim:model")) return@mapNotNull null
            val name = templateName(path)
            val nodes = TemplateParser(name, source).parse()
            val root = nodes.firstOrNull { it is ElementNode } as? ElementNode
                ?: error("$name: a typed template needs a root element")
            val declarations = nodes.asSequence().flatMap(Node::elements).filter { "thim:model" in it.attributes }.toList()
            require(declarations.size == 1) { "$name: expected exactly one thim:model attribute" }
            require(declarations.single() === root) { "$name: thim:model must be on the root element" }
            val modelName = requireNotNull(root.attributes.remove("thim:model")) { "$name: thim:model needs a value" }
            require(modelName.matches(modelNamePattern)) { "$name: invalid model name '$modelName'" }
            TemplateSource(name, modelName, nodes)
        }
    }

    private fun templateName(path: Path): String = templatesDirectory.relativize(path)
        .toString()
        .replace(path.fileSystem.separator, "/")
        .removeSuffix(".html")

    private fun validateConfiguration() {
        require(Files.isDirectory(templatesDirectory)) { "Template directory does not exist: $templatesDirectory" }
        require(generatedPackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
            "Invalid generated package '$generatedPackage'"
        }
        require(registryName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid registry name '$registryName'" }
    }

    private fun requiredPath(environment: SymbolProcessorEnvironment, key: String): Path =
        Path.of(requireNotNull(environment.options[key]) { "Missing KSP option '$key'" }).toAbsolutePath().normalize()

    private companion object {
        val modelNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

private fun Node.elements(): Sequence<ElementNode> = when (this) {
    is ElementNode -> sequenceOf(this) + children.asSequence().flatMap(Node::elements)
    is RawNode -> emptySequence()
}
