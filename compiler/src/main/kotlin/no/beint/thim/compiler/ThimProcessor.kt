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
    val model: KSClassDeclaration,
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
    private val modelPackages = environment.options["thim.modelPackages"]
        .orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
    private val strictTemplates = environment.options["thim.strictTemplates"].toBoolean()
    private val failOnUnusedMessages = environment.options["thim.failOnUnusedMessages"].toBoolean()
    private val strictModels = environment.options["thim.strictModels"].toBoolean()
    private val failOnUnusedProperties = environment.options["thim.failOnUnusedProperties"].toBoolean()
    private val forbiddenModelAnnotations = environment.options["thim.forbiddenModelAnnotations"]
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        ?: defaultForbiddenModelAnnotations
    private val validateRoutes = environment.options["thim.validateRoutes"]?.toBoolean() ?: true
    private val externalPaths = environment.options["thim.externalPaths"]
        .orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
    private var completed = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (completed) return emptyList()

        try {
            validateConfiguration()
            val parsed = parsedTemplates()
            val expander = FragmentExpander(parsed)
            val templates = typedTemplates(resolver, parsed).map { template ->
                template.copy(nodes = expander.expand(template.name, template.nodes))
            }
            if (templates.isEmpty()) {
                completed = true
                return emptyList()
            }
            require(templates.map { it.model.qualifiedName?.asString() }.distinct().size == templates.size) {
                "A page model can only be assigned to one template"
            }
            if (strictModels) {
                val checker = StrictModelChecker(forbiddenModelAnnotations)
                templates.forEach { checker.check(it.model) }
            }

            val catalog = MessageCatalog.load(messagesDirectory)
            val routeCatalog = if (validateRoutes) {
                RouteCatalog.load(resolver, externalPaths)
            } else {
                RouteCatalog(emptyList(), emptyList())
            }
            val staticContent = StaticContent()
            val generator = RendererGenerator(catalog, routeCatalog, staticContent, registryName, strictModels)
            val compiled = templates.map { template ->
                generator.compile(template.name, template.model, template.nodes)
            }
            if (failOnUnusedMessages) catalog.requireAllUsed()
            if (strictModels) reportUnusedProperties(templates, generator)
            val documentChecker = DocumentChecker(logger::warn)
            templates.forEach { documentChecker.check(it.nodes) }
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

    private fun reportUnusedProperties(templates: List<TemplateSource>, generator: RendererGenerator) {
        val unused = templates.flatMap { template ->
            val used = generator.usedRootProperties(template.model)
            modelProperties(template.model)
                .filter { property -> property.aliases.none(used::contains) }
                .map { "${template.model.qualifiedName?.asString()}.${it.name} is not used by template '${template.name}'" }
        }
        if (unused.isEmpty()) return
        if (failOnUnusedProperties) {
            diagnostic("THIM-MODEL-UNUSED-PROPERTY", null, unused.joinToString("; "))
        } else {
            unused.forEach { logger.warn("THIM-MODEL-UNUSED-PROPERTY $it") }
        }
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
            output.appendLine("    public boolean supportsReturnType(Class<?> returnType) {")
            output.appendLine("        return returnType != Object.class && (" + compiled.joinToString(" ||\n            ") {
                "returnType.isAssignableFrom(${it.model.qualifiedName!!.asString()}.class)"
            } + ");")
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

    private fun parsedTemplates(): Map<String, List<Node>> {
        val paths = mutableListOf<Path>()
        Files.walk(templatesDirectory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".html") }.forEach(paths::add)
        }
        return paths.sorted().associate { path ->
            val source = Files.readString(path, StandardCharsets.UTF_8)
            val name = templateName(path)
            name to TemplateParser(name, source).parse()
        }
    }

    private fun typedTemplates(resolver: Resolver, parsed: Map<String, List<Node>>): List<TemplateSource> =
        parsed.mapNotNull { (name, nodes) ->
            val root = nodes.firstOrNull { it is ElementNode } as? ElementNode
            require(nodes.asSequence().flatMap(Node::elements).none { element ->
                element.attributes.keys.any { it.startsWith("thim:") }
            }) { "$name: thim:* template attributes are not part of the language" }
            val candidates = modelPackages.map { "$it.${conventionalModelName(name)}" }
            val models = candidates.mapNotNull { candidate ->
                resolver.getClassDeclarationByName(resolver.getKSNameFromString(candidate))
            }
            require(models.size <= 1) { "$name: model '${conventionalModelName(name)}' exists in multiple configured packages" }
            val model = models.singleOrNull()
            if (model == null) {
                if (strictTemplates && !nodes.hasFragments()) {
                    error("$name: model '${conventionalModelName(name)}' does not exist in $modelPackages")
                }
                return@mapNotNull null
            }
            require(root != null) { "$name: a typed template needs a root element" }
            TemplateSource(name, model, nodes)
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
        modelPackages.forEach { modelPackage ->
            require(modelPackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
                "Invalid model package '$modelPackage'"
            }
        }
    }

    private fun requiredPath(environment: SymbolProcessorEnvironment, key: String): Path =
        Path.of(requireNotNull(environment.options[key]) { "Missing KSP option '$key'" }).toAbsolutePath().normalize()

    private companion object {
        fun conventionalModelName(templateName: String): String = templateName
            .split(Regex("[^A-Za-z0-9]+"))
            .filter(String::isNotEmpty)
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Page"
    }
}

private fun List<Node>.hasFragments(): Boolean =
    asSequence().flatMap(Node::elements).any { "th:fragment" in it.attributes }

internal fun Node.elements(): Sequence<ElementNode> = when (this) {
    is ElementNode -> sequenceOf(this) + children.asSequence().flatMap(Node::elements)
    is RawNode -> emptySequence()
}
