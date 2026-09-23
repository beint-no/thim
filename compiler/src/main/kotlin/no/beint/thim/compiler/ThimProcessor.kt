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
    private val defaultLocale = environment.options["thim.defaultLocale"]
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: "en"
    private val supportedLocales = environment.options["thim.supportedLocales"]
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?: emptyList()
    private val generatedPackage = environment.options["thim.package"] ?: "thim.generated"
    private val registryName = environment.options["thim.registry"] ?: "ThimTemplates"
    private val modelPackages = environment.options["thim.modelPackages"]
        .orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
    private val strictTemplates = environment.options["thim.strictTemplates"]?.toBoolean() ?: true
    private val failOnUnusedMessages = environment.options["thim.failOnUnusedMessages"]?.toBoolean() ?: true
    private val failOnUnusedFragments = environment.options["thim.failOnUnusedFragments"]?.toBoolean() ?: true
    private val strictModels = environment.options["thim.strictModels"]?.toBoolean() ?: true
    private val forbiddenModelAnnotations = environment.options["thim.forbiddenModelAnnotations"]
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        ?: defaultForbiddenModelAnnotations
    private val validateRoutes = environment.options["thim.validateRoutes"]?.toBoolean() ?: true
    private val generateRoutes = environment.options["thim.generateRoutes"]?.toBoolean() ?: false
    private val routesName = environment.options["thim.routesName"]
        ?: registryName.removeSuffix("Templates") + "Routes"
    private val generateMessages = environment.options["thim.generateMessages"]?.toBoolean() ?: true
    private val messagesName = environment.options["thim.messagesName"]
        ?: registryName.removeSuffix("Templates") + "Messages"
    private val catalogId = environment.options["thim.catalogId"]
        ?: messagesDirectory.toAbsolutePath().normalize().toString()
    private val trustedPaths = environment.options["thim.trustedPaths"]
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
            val catalog = MessageCatalog.load(messagesDirectory, defaultLocale, supportedLocales)
            if (templates.isEmpty()) {
                if (catalog.definitions().isNotEmpty()) {
                    generateMessages(catalog, emptyArray())
                }
                completed = true
                return emptyList()
            }
            require(templates.map { it.model.qualifiedName?.asString() }.distinct().size == templates.size) {
                "A page model can only be assigned to one template"
            }
            val problems = mutableListOf<String>()
            if (strictModels) {
                val checker = StrictModelChecker(forbiddenModelAnnotations)
                templates.forEach { checker.check(it.model) }
                problems += checker.problems
            }

            val extractedRoutes = if (validateRoutes || generateRoutes) {
                RouteCatalog.load(resolver, trustedPaths)
            } else {
                RouteCatalog(emptyList(), emptyList())
            }
            val routeCatalog = if (validateRoutes) {
                extractedRoutes
            } else {
                RouteCatalog(emptyList(), emptyList(), extractedRoutes.files)
            }
            val generator = RendererGenerator(catalog, routeCatalog, strictModels)
            val statics = List(RENDERER_FILES) { StaticContent() }
            val compiled = templates.map { template ->
                val file = rendererFile(template.model)
                generator.compile(template.name, template.model, template.nodes, statics[file], holderName(file))
            }
            problems += generator.errors
            collect(problems) { if (strictModels) reportUnusedProperties(templates, generator) }
            collect(problems) { reportUnusedFragments(expander) }
            val documentChecker = DocumentChecker(logger::warn)
            templates.forEach { documentChecker.check(it.nodes) }
            problems += documentChecker.problems
            if (problems.isNotEmpty()) {
                problems.forEach(logger::error)
                completed = true
                return emptyList()
            }
            generate(compiled, statics, extractedRoutes)
            if (catalog.definitions().isNotEmpty()) {
                val files = (compiled.mapNotNull { it.model.containingFile } + extractedRoutes.files).distinct().toTypedArray()
                generateMessages(catalog, files)
            }
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

    private inline fun collect(problems: MutableList<String>, block: () -> Unit) {
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            problems += exception.message ?: "Thim compilation failed"
        } catch (exception: IllegalStateException) {
            problems += exception.message ?: "Thim compilation failed"
        }
    }

    private fun reportUnusedFragments(expander: FragmentExpander) {
        val unusedParameters = expander.unusedParameters()
        if (unusedParameters.isNotEmpty()) {
            if (failOnUnusedFragments) {
                diagnostic(
                    "THIM-FRAGMENT-PARAMETER-UNUSED",
                    null,
                    "fragment parameters never used: ${unusedParameters.joinToString(", ")}",
                )
            } else {
                unusedParameters.forEach {
                    logger.warn("THIM-FRAGMENT-PARAMETER-UNUSED '$it' is never used by the fragment")
                }
            }
        }
        val unused = expander.unusedFragments()
        if (unused.isNotEmpty()) {
            if (failOnUnusedFragments) {
                diagnostic("THIM-FRAGMENT-UNUSED", null, "fragments never used by a compiled page: ${unused.joinToString(", ")}")
            } else {
                unused.forEach { logger.warn("THIM-FRAGMENT-UNUSED fragment '$it' is never used by a compiled page") }
            }
        }
    }

    private fun reportUnusedProperties(templates: List<TemplateSource>, generator: RendererGenerator) {
        val unused = templates.flatMap { template ->
            val used = generator.usedRootProperties(template.model)
            modelProperties(template.model)
                .filter { property -> property.aliases.none(used::contains) }
                .map { "${template.model.qualifiedName?.asString()}.${it.name} is not used by template '${template.name}'" }
        }
        if (unused.isEmpty()) return
        diagnostic("THIM-MODEL-UNUSED-PROPERTY", null, unused.joinToString("; "))
    }

    /**
     * Renderers are grouped into a fixed number of source files by a stable hash of the
     * page-model name, each with its own static resource. A renderer references only its
     * file's holder class, never the registry or another file, so an unchanged file
     * regenerates byte-identical output and Gradle's incremental Java compilation recompiles
     * only the file whose template changed plus the registry. A fixed count keeps KSP's
     * per-file overhead small; membership depends on the model name alone, so adding or
     * removing a template touches one file.
     */
    private fun generate(compiled: List<CompiledTemplate>, statics: List<StaticContent>, routeCatalog: RouteCatalog) {
        val files = (compiled.mapNotNull { it.model.containingFile } + routeCatalog.files).distinct().toTypedArray()
        val dependencies = Dependencies(aggregating = true, *files)
        compiled.groupBy { rendererFile(it.model) }.toSortedMap().forEach { (file, members) ->
            val holder = holderName(file)
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = generatedPackage,
                fileName = holder,
                extensionName = "bin",
            ).use { it.write(statics[file].bytes()) }
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = generatedPackage,
                fileName = holder,
                extensionName = "java",
            ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
                output.appendLine("package $generatedPackage;")
                output.appendLine()
                output.appendLine("import java.io.IOException;")
                output.appendLine("import no.beint.thim.HtmlOutput;")
                output.appendLine("import no.beint.thim.RenderContext;")
                output.appendLine()
                output.appendLine("final class $holder {")
                output.appendLine("    static final byte[] STATIC = HtmlOutput.resource($holder.class, \"$holder.bin\");")
                output.appendLine()
                output.appendLine("    private $holder() {}")
                members.sortedBy { it.rendererName }.forEach { template ->
                    output.appendLine()
                    output.append(template.source)
                }
                output.appendLine("}")
            }
        }
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = generatedPackage,
            fileName = registryName,
            extensionName = "java",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            val entries = compiled.map {
                RegistryEntry(it.model.qualifiedName!!.asString(), rendererReference(it), it.usesRequestDataValues)
            }
            output.append(RegistryGenerator(entries).generate(generatedPackage, registryName))
        }
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = "META-INF/services/no.beint.thim.TemplateSet",
            extensionName = "",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.appendLine("$generatedPackage.$registryName")
        }
        if (generateRoutes) {
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = generatedPackage,
                fileName = routesName,
                extensionName = "kt",
            ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
                output.append(RouteGenerator(routeCatalog).generate(generatedPackage, routesName))
            }
        }
    }

    /**
     * The usage manifest is written whenever a catalog exists, even when this module skips
     * the typed factory class. Build-wide dead-key detection then sees this module's template
     * usage, so a module can share a catalog whose factories another module generates.
     */
    private fun generateMessages(catalog: MessageCatalog, files: Array<com.google.devtools.ksp.symbol.KSFile>) {
        val dependencies = Dependencies(aggregating = true, *files)
        val generator = MessageGenerator(catalog)
        if (generateMessages) {
            codeGenerator.createNewFile(
                dependencies = dependencies,
                packageName = generatedPackage,
                fileName = messagesName,
                extensionName = "java",
            ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
                output.append(generator.generate(generatedPackage, messagesName))
            }
        }
        codeGenerator.createNewFileByPath(
            dependencies = dependencies,
            path = "META-INF/thim/messages/${generatedPackage.replace('.', '_')}_$messagesName.usage",
            extensionName = "",
        ).bufferedWriter(StandardCharsets.UTF_8).use { output ->
            output.append(generator.usageManifest(generatedPackage, messagesName, catalogId, failOnUnusedMessages))
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
        require(routesName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid routes name '$routesName'" }
        require(messagesName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid messages name '$messagesName'" }
        modelPackages.forEach { modelPackage ->
            require(modelPackage.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
                "Invalid model package '$modelPackage'"
            }
        }
    }

    private fun requiredPath(environment: SymbolProcessorEnvironment, key: String): Path =
        Path.of(requireNotNull(environment.options[key]) { "Missing KSP option '$key'" }).toAbsolutePath().normalize()

    private fun rendererFile(model: KSClassDeclaration): Int =
        Math.floorMod(model.qualifiedName!!.asString().hashCode(), RENDERER_FILES)

    private fun holderName(file: Int): String = "${registryName}Part$file"

    private fun rendererReference(template: CompiledTemplate): String =
        "${holderName(rendererFile(template.model))}.${template.rendererName}"

    private companion object {
        /** Generated renderer source files per module; see [generate]. */
        const val RENDERER_FILES = 32

        fun conventionalModelName(templateName: String): String = templateName
            .split(NON_ALPHANUMERIC)
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

/** Word separators for generated identifiers, compiled once rather than per catalog key or template. */
internal val NON_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")
