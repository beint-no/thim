package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

@CacheableTask
public abstract class ThimExtractExternalRoutes extends DefaultTask {
    static final String HEADER = "thim-external-routes\t1";

    private static final String REQUEST_MAPPING = "Lorg/springframework/web/bind/annotation/RequestMapping;";
    private static final String PATH_VARIABLE = "Lorg/springframework/web/bind/annotation/PathVariable;";
    private static final String REQUEST_PARAM = "Lorg/springframework/web/bind/annotation/RequestParam;";
    private static final Map<String, Set<String>> MAPPING_ANNOTATIONS = Map.of(
            "Lorg/springframework/web/bind/annotation/GetMapping;", Set.of("GET"),
            "Lorg/springframework/web/bind/annotation/PostMapping;", Set.of("POST"),
            "Lorg/springframework/web/bind/annotation/PutMapping;", Set.of("PUT"),
            "Lorg/springframework/web/bind/annotation/PatchMapping;", Set.of("PATCH"),
            "Lorg/springframework/web/bind/annotation/DeleteMapping;", Set.of("DELETE"),
            REQUEST_MAPPING, Set.of()
    );
    private static final Map<String, String> JAVA_TYPES = Map.ofEntries(
            Map.entry("java.lang.Boolean", "kotlin.Boolean"),
            Map.entry("java.lang.Byte", "kotlin.Byte"),
            Map.entry("java.lang.Character", "kotlin.Char"),
            Map.entry("java.lang.Double", "kotlin.Double"),
            Map.entry("java.lang.Float", "kotlin.Float"),
            Map.entry("java.lang.Integer", "kotlin.Int"),
            Map.entry("java.lang.Long", "kotlin.Long"),
            Map.entry("java.lang.Short", "kotlin.Short"),
            Map.entry("java.lang.String", "kotlin.String"),
            Map.entry("java.util.Collection", "kotlin.collections.Collection"),
            Map.entry("java.util.List", "kotlin.collections.List"),
            Map.entry("java.util.Set", "kotlin.collections.Set")
    );
    private static final Set<String> UNSUPPORTED_URL_TYPES = Set.of(
            "java.util.Map",
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
            "jakarta.servlet.http.Part",
            "org.springframework.util.MultiValueMap",
            "org.springframework.web.multipart.MultipartFile"
    );
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/+");

    @Classpath
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getProjectClasses();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void extractRoutes() throws IOException {
        var classFile = ClassFile.of();
        var seenClasses = new HashSet<String>();
        var rows = new TreeSet<String>();

        var inputs = getProjectClasses().getFiles().stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .toList();

        for (var input : inputs) {
            if (!input.exists()) continue;
            if (input.isDirectory()) {
                var classPaths = new ArrayList<Path>();
                try (var stream = Files.walk(input.toPath())) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".class"))
                            .forEach(classPaths::add);
                }
                classPaths.sort(Comparator.comparing(Path::toAbsolutePath));
                for (var path : classPaths) {
                    inspectClass(classFile.parse(path), seenClasses, rows);
                }
            } else if (input.isFile() && input.getName().endsWith(".jar")) {
                try (var jar = new JarFile(input)) {
                    var entries = jar.stream()
                            .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                            .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
                            .toList();
                    for (var entry : entries) {
                        try (var in = jar.getInputStream(entry)) {
                            inspectClass(classFile.parse(in.readAllBytes()), seenClasses, rows);
                        }
                    }
                }
            }
        }

        var lines = new ArrayList<String>(rows.size() + 1);
        lines.add(HEADER);
        lines.addAll(rows);
        var output = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static void inspectClass(ClassModel model, Set<String> seenClasses, Set<String> rows) {
        if (!seenClasses.add(model.thisClass().asInternalName())) return;

        var classPrefixes = classPrefixes(model);
        for (var method : model.methods()) {
            if (method.flags().has(AccessFlag.SYNTHETIC) || method.flags().has(AccessFlag.BRIDGE)) continue;
            var annotationsAttr = method.findAttribute(Attributes.runtimeVisibleAnnotations());
            if (annotationsAttr.isEmpty()) continue;

            List<Param> pathParams = null;
            List<Param> queryParams = null;

            for (var annotation : annotationsAttr.get().annotations()) {
                var descriptor = annotation.className().stringValue();
                var fixedMethods = MAPPING_ANNOTATIONS.get(descriptor);
                if (fixedMethods == null) continue;

                var httpMethods = fixedMethods.isEmpty() ? requestMethods(annotation) : fixedMethods;
                var methodPaths = paths(annotation);
                if (pathParams == null) {
                    pathParams = extractParameters(method, PATH_VARIABLE, false);
                    queryParams = extractParameters(method, REQUEST_PARAM, true);
                }
                var methodsField = httpMethods.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
                var queryField = formatParams(queryParams);

                for (var prefix : classPrefixes) {
                    for (var path : methodPaths) {
                        var pattern = combine(prefix, path);
                        var variables = extractPathVariables(pattern);
                        var matchedPathParams = pathParams.stream()
                                .filter(param -> variables.contains(param.name()))
                                .toList();
                        rows.add("route\t" + methodsField + "\t" + pattern + "\t" +
                                formatParams(matchedPathParams) + "\t" + queryField);
                    }
                }
            }
        }
    }

    private static List<String> classPrefixes(ClassModel model) {
        var attr = model.findAttribute(Attributes.runtimeVisibleAnnotations());
        if (attr.isEmpty()) return List.of("");
        for (var annotation : attr.get().annotations()) {
            if (REQUEST_MAPPING.equals(annotation.className().stringValue())) {
                return paths(annotation);
            }
        }
        return List.of("");
    }

    private static List<String> paths(Annotation annotation) {
        var values = new LinkedHashSet<String>();
        for (var element : annotation.elements()) {
            var name = element.name().stringValue();
            if (!name.equals("value") && !name.equals("path")) continue;
            collectStrings(element.value(), values);
        }
        return values.isEmpty() ? List.of("") : List.copyOf(values);
    }

    private static Set<String> requestMethods(Annotation annotation) {
        var methods = new TreeSet<String>();
        for (var element : annotation.elements()) {
            if (!element.name().stringValue().equals("method")) continue;
            collectEnumNames(element.value(), methods);
        }
        return methods;
    }

    private static void collectStrings(AnnotationValue value, Set<String> out) {
        if (value instanceof AnnotationValue.OfString str) {
            out.add(str.stringValue());
        } else if (value instanceof AnnotationValue.OfArray arr) {
            for (var item : arr.values()) {
                collectStrings(item, out);
            }
        }
    }

    private static void collectEnumNames(AnnotationValue value, Set<String> out) {
        if (value instanceof AnnotationValue.OfEnum enumVal) {
            var name = enumVal.constantName().stringValue();
            if (!name.isBlank()) out.add(name);
        } else if (value instanceof AnnotationValue.OfArray arr) {
            for (var item : arr.values()) {
                collectEnumNames(item, out);
            }
        }
    }

    private static List<Param> extractParameters(MethodModel method, String annotationDescriptor, boolean query) {
        var paramAnnAttr = method.findAttribute(Attributes.runtimeVisibleParameterAnnotations());
        if (paramAnnAttr.isEmpty()) return List.of();

        var paramAnnotations = paramAnnAttr.get().parameterAnnotations();
        var paramTypes = method.methodTypeSymbol().parameterList();
        var paramNames = method.findAttribute(Attributes.methodParameters())
                .map(attr -> attr.parameters().stream()
                        .map(info -> info.name().map(n -> n.stringValue()).orElse(null))
                        .toList())
                .orElse(List.of());

        var result = new ArrayList<Param>();
        var count = Math.min(paramAnnotations.size(), paramTypes.size());
        for (var i = 0; i < count; i++) {
            Annotation match = null;
            for (var ann : paramAnnotations.get(i)) {
                if (annotationDescriptor.equals(ann.className().stringValue())) {
                    match = ann;
                    break;
                }
            }
            if (match == null) continue;

            var explicitName = firstNonBlankString(match, "name", "value");
            var name = explicitName != null ? explicitName : (i < paramNames.size() ? paramNames.get(i) : null);
            if (name == null || name.isBlank()) continue;
            if (query && isSecretQueryName(name)) continue;

            var typeName = kotlinType(paramTypes.get(i));
            if (query && UNSUPPORTED_URL_TYPES.contains(rawTypeName(paramTypes.get(i)))) continue;
            result.add(new Param(name, typeName));
        }
        return result;
    }

    private static String firstNonBlankString(Annotation annotation, String... names) {
        var allowed = Set.of(names);
        for (var element : annotation.elements()) {
            if (!allowed.contains(element.name().stringValue())) continue;
            if (element.value() instanceof AnnotationValue.OfString str && !str.stringValue().isBlank()) {
                return str.stringValue();
            }
        }
        return null;
    }

    private static String kotlinType(ClassDesc desc) {
        if (desc.isPrimitive()) {
            return switch (desc.descriptorString()) {
                case "Z" -> "kotlin.Boolean";
                case "B" -> "kotlin.Byte";
                case "C" -> "kotlin.Char";
                case "D" -> "kotlin.Double";
                case "F" -> "kotlin.Float";
                case "I" -> "kotlin.Int";
                case "J" -> "kotlin.Long";
                case "S" -> "kotlin.Short";
                default -> "kotlin.Any";
            };
        }
        var raw = rawTypeName(desc);
        return JAVA_TYPES.getOrDefault(raw, raw);
    }

    private static String rawTypeName(ClassDesc desc) {
        if (desc.isArray()) return "kotlin.Any";
        var descriptor = desc.descriptorString();
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.').replace('$', '.');
        }
        return "kotlin.Any";
    }

    private static boolean isSecretQueryName(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("password") || normalized.equals("passwd") || normalized.equals("pwd") ||
                normalized.equals("secret") || normalized.endsWith("password");
    }

    private static String combine(String prefix, String path) {
        var trimmedPrefix = trimSlashes(prefix);
        var trimmedPath = trimSlashes(path);
        var joined = REPEATED_SLASHES.matcher("/" + trimmedPrefix + "/" + trimmedPath).replaceAll("/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    private static String trimSlashes(String value) {
        var start = 0;
        var end = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
    }

    private static Set<String> extractPathVariables(String pattern) {
        var vars = new HashSet<String>();
        for (var segment : pattern.split("/")) {
            if (segment.startsWith("{") && segment.endsWith("}") && segment.length() > 2) {
                var inner = segment.substring(1, segment.length() - 1);
                var colon = inner.indexOf(':');
                var name = colon >= 0 ? inner.substring(0, colon) : inner;
                if (name.startsWith("*")) name = name.substring(1);
                if (!name.isEmpty()) vars.add(name);
            }
        }
        return vars;
    }

    private static String formatParams(List<Param> params) {
        if (params.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(params.get(i).name()).append('=').append(params.get(i).type());
        }
        return sb.toString();
    }

    private record Param(String name, String type) {}
}
