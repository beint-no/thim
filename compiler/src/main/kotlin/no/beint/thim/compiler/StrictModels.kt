package no.beint.thim.compiler

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

internal val defaultForbiddenModelAnnotations = setOf(
    "jakarta.persistence.Entity",
    "jakarta.persistence.Embeddable",
    "jakarta.persistence.MappedSuperclass",
    "javax.persistence.Entity",
    "javax.persistence.Embeddable",
    "javax.persistence.MappedSuperclass",
)

internal data class ModelProperty(
    val name: String,
    val type: KSType,
    val mutable: Boolean,
    val aliases: Set<String>,
)

/**
 * The properties a template expression can reach on a model class, mirroring the three
 * resolution strategies of Scope.property: declared properties, primary constructor
 * parameters (Java records), and getter functions (Java beans). Aliases hold every
 * expression spelling that resolves to the same accessor.
 */
internal fun modelProperties(declaration: KSClassDeclaration): List<ModelProperty> {
    val properties = LinkedHashMap<String, ModelProperty>()
    declaration.getAllProperties().filterNot { it.isPrivate() }.forEach { property ->
        val name = property.simpleName.asString()
        properties[name] = ModelProperty(name, property.type.resolve(), property.isMutable, setOf(name))
    }
    declaration.primaryConstructor?.parameters?.forEach { parameter ->
        val name = parameter.name?.asString() ?: return@forEach
        properties.putIfAbsent(name, ModelProperty(name, parameter.type.resolve(), mutable = false, setOf(name)))
    }
    declaration.getDeclaredFunctions().filterNot { it.isPrivate() }.forEach { function ->
        val name = function.simpleName.asString()
        if (function.parameters.isNotEmpty() || function.returnType == null) return@forEach
        val property = when {
            name.startsWith("get") && name.length > 3 && name[3].isUpperCase() ->
                name.substring(3).replaceFirstChar(Char::lowercaseChar)
            name.startsWith("is") && name.length > 2 && name[2].isUpperCase() -> name
            else -> return@forEach
        }
        val aliases = when {
            name.startsWith("is") -> setOf(name, name.substring(2).replaceFirstChar(Char::lowercaseChar))
            else -> setOf(property, name)
        }
        properties.putIfAbsent(property, ModelProperty(property, function.returnType!!.resolve(), mutable = false, aliases))
    }
    return properties.values.toList()
}

/**
 * Enforces the strict closed-world contract on a page model and every user type reachable
 * from it through properties: immutable data, concrete types, no persistence-managed
 * classes, and typed materialized collections.
 */
internal class StrictModelChecker(private val forbiddenAnnotations: Set<String>) {
    private val visited = mutableSetOf<String>()

    fun check(model: KSClassDeclaration) {
        checkClass(model, "page model '${model.qualifiedName?.asString()}'")
    }

    private fun checkClass(declaration: KSClassDeclaration, root: String) {
        val name = declaration.qualifiedName?.asString() ?: return
        if (!visited.add(name)) return
        declaration.annotations.forEach { annotation ->
            val annotationName = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
            requireDiagnostic(annotationName !in forbiddenAnnotations, "THIM-MODEL-FORBIDDEN-TYPE", null) {
                "$root: $name is annotated with @$annotationName; strict page models cannot reference persistence-managed types"
            }
        }
        modelProperties(declaration).forEach { property ->
            requireDiagnostic(!property.mutable, "THIM-MODEL-MUTABLE", null) {
                "$root: $name.${property.name} is mutable; strict page models must be immutable"
            }
            checkType(name, property.name, property.type, root)
        }
        declaration.getDeclaredFunctions().filterNot { it.isPrivate() }.forEach { function ->
            val functionName = function.simpleName.asString()
            requireDiagnostic(
                !(functionName.startsWith("set") && functionName.length > 3 && functionName[3].isUpperCase() && function.parameters.size == 1),
                "THIM-MODEL-MUTABLE",
                null,
            ) {
                "$root: $name.$functionName() makes the model mutable; strict page models must be immutable"
            }
        }
    }

    private fun checkType(owner: String, property: String, type: KSType, root: String) {
        val typeName = type.declaration.qualifiedName?.asString() ?: return
        requireDiagnostic(typeName !in dynamicTypes, "THIM-MODEL-DYNAMIC-TYPE", null) {
            "$root: $owner.$property is typed $typeName; strict page models need concrete types"
        }
        val supertypes = sequenceOf(type) + ((type.declaration as? KSClassDeclaration)?.getAllSuperTypes() ?: emptySequence())
        val names = supertypes.mapNotNull { it.declaration.qualifiedName?.asString() }.toSet()
        requireDiagnostic(names.none { it in mapTypes }, "THIM-MODEL-DYNAMIC-TYPE", null) {
            "$root: $owner.$property is map-shaped ($typeName); strict page models need typed properties, not dynamic maps"
        }
        val iterable = supertypes.firstOrNull { it.declaration.qualifiedName?.asString() in iterableTypes }
        if (iterable != null) {
            val element = iterable.arguments.firstOrNull()?.type?.resolve()
                ?: diagnostic(
                    "THIM-MODEL-DYNAMIC-TYPE",
                    null,
                    "$root: $owner.$property is a raw collection; strict page models need a typed element",
                )
            checkType(owner, "$property element", element, root)
            return
        }
        val declaration = type.declaration as? KSClassDeclaration ?: return
        if (!isPlatformType(typeName)) checkClass(declaration, root)
    }

    private companion object {
        val dynamicTypes = setOf("kotlin.Any", "java.lang.Object")
        val mapTypes = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap", "java.util.Map")
        val iterableTypes = setOf(
            "kotlin.collections.Iterable", "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Set",
            "kotlin.collections.MutableSet", "kotlin.Array", "java.lang.Iterable", "java.util.Collection",
            "java.util.List", "java.util.Set",
        )

        fun isPlatformType(name: String): Boolean =
            name.startsWith("kotlin.") || name.startsWith("java.") || name.startsWith("javax.") ||
                name.substringBeforeLast('.') == "no.beint.thim"
    }
}
