package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@CacheableTask
public abstract class ThimMessageUsageCheck extends DefaultTask {
    private static final String MANIFEST_DIRECTORY = "META-INF/thim/messages";

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProjectOutputs();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void checkUsage() throws IOException {
        var manifests = new ArrayList<Path>();
        var classFiles = new ArrayList<Path>();
        for (var output : getProjectOutputs().getFiles()) {
            if (!output.isDirectory()) continue;
            try (var files = Files.walk(output.toPath())) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    if (path.toString().endsWith(".class")) {
                        classFiles.add(path);
                    } else if (isUsageManifest(output.toPath(), path)) {
                        manifests.add(path);
                    }
                });
            }
        }

        var catalogs = new LinkedHashMap<String, CatalogUsage>();
        var methods = new HashMap<MethodReference, List<MessageIdentity>>();
        var references = new HashMap<String, List<MessageIdentity>>();
        var generatedClasses = new HashSet<String>();
        for (var path : manifests.stream().distinct().sorted().toList()) {
            var manifest = Manifest.read(path);
            var catalog = catalogs.computeIfAbsent(manifest.catalogId(), CatalogUsage::new);
            catalog.add(manifest, path);
            generatedClasses.add(manifest.apiClass());
            for (var definition : manifest.definitions()) {
                var identity = new MessageIdentity(manifest.catalogId(), definition.key());
                methods.computeIfAbsent(definition.method(), ignored -> new ArrayList<>()).add(identity);
                references.computeIfAbsent(definition.reference(), ignored -> new ArrayList<>()).add(identity);
            }
        }

        for (var path : classFiles.stream().distinct().sorted().toList()) {
            var classUsage = ClassUsage.read(path);
            if (isGeneratedMessageClass(classUsage.className(), generatedClasses)) continue;
            for (var method : classUsage.methods()) {
                for (var identity : methods.getOrDefault(method, List.of())) {
                    catalogs.get(identity.catalogId()).used.add(identity.key());
                }
            }
            for (var reference : classUsage.strings()) {
                for (var identity : references.getOrDefault(reference, List.of())) {
                    catalogs.get(identity.catalogId()).used.add(identity.key());
                }
            }
        }

        var failures = new LinkedHashMap<String, Set<String>>();
        for (var catalog : catalogs.values()) {
            if (!catalog.enforce) continue;
            var unused = new TreeSet<>(catalog.definitions);
            unused.removeAll(catalog.used);
            if (!unused.isEmpty()) failures.put(catalog.id, unused);
        }
        writeReport(catalogs, failures);
        if (!failures.isEmpty()) {
            throw new GradleException(failures.entrySet().stream()
                    .map(entry -> "Unused messages in " + entry.getKey() + ": " + entry.getValue())
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElseThrow());
        }
    }

    private boolean isUsageManifest(Path root, Path path) {
        var relative = root.relativize(path).toString().replace('\\', '/');
        return relative.startsWith(MANIFEST_DIRECTORY + "/") && relative.endsWith(".usage");
    }

    private boolean isGeneratedMessageClass(String className, Set<String> generatedClasses) {
        return generatedClasses.stream().anyMatch(outer -> className.equals(outer) || className.startsWith(outer + "$"));
    }

    private void writeReport(Map<String, CatalogUsage> catalogs, Map<String, Set<String>> failures) throws IOException {
        var report = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        var json = new StringBuilder("{\n  \"status\": \"")
                .append(failures.isEmpty() ? "valid" : "invalid")
                .append("\",\n  \"catalogs\": [");
        var separator = "";
        for (var catalog : catalogs.values()) {
            var unused = failures.getOrDefault(catalog.id, Set.of());
            json.append(separator).append("\n    {\"id\": \"").append(json(catalog.id))
                    .append("\", \"messages\": ").append(catalog.definitions.size())
                    .append(", \"unused\": ").append(unused.size())
                    .append(", \"unusedKeys\": [");
            var keySeparator = "";
            for (var key : unused) {
                json.append(keySeparator).append("\"").append(json(key)).append("\"");
                keySeparator = ", ";
            }
            json.append("]}");
            separator = ",";
        }
        json.append(catalogs.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        Files.writeString(report, json, StandardCharsets.UTF_8);
    }

    private String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record MessageIdentity(String catalogId, String key) {}

    private record MethodReference(String owner, String name) {}

    private record Definition(String key, MethodReference method, String reference) {}

    private record Manifest(
            String catalogId,
            boolean enforce,
            String apiClass,
            Set<Definition> definitions,
            Set<String> templateUsage
    ) {
        private static Manifest read(Path path) throws IOException {
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals("thim-message-usage\t1")) {
                throw new GradleException("Unsupported Thim message usage manifest " + path);
            }
            String catalogId = null;
            String apiClass = null;
            var enforce = false;
            var definitions = new LinkedHashSet<Definition>();
            var templateUsage = new LinkedHashSet<String>();
            for (var line : lines.subList(1, lines.size())) {
                if (line.isBlank()) continue;
                var fields = line.split("\\t", -1);
                switch (fields[0]) {
                    case "catalog" -> catalogId = decoded(field(fields, 1, path));
                    case "enforce" -> enforce = Boolean.parseBoolean(field(fields, 1, path));
                    case "api" -> apiClass = field(fields, 1, path);
                    case "definition" -> definitions.add(new Definition(
                            decoded(field(fields, 1, path)),
                            new MethodReference(field(fields, 2, path), field(fields, 3, path)),
                            decoded(field(fields, 4, path))
                    ));
                    case "template" -> templateUsage.add(decoded(field(fields, 1, path)));
                    default -> throw new GradleException("Unknown entry in Thim message usage manifest " + path + ": " + fields[0]);
                }
            }
            if (catalogId == null || apiClass == null) {
                throw new GradleException("Incomplete Thim message usage manifest " + path);
            }
            return new Manifest(catalogId, enforce, apiClass, definitions, templateUsage);
        }

        private static String field(String[] fields, int index, Path path) {
            if (fields.length <= index || fields[index].isEmpty()) {
                throw new GradleException("Invalid Thim message usage manifest " + path);
            }
            return fields[index];
        }

        private static String decoded(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }

    private static final class CatalogUsage {
        private final String id;
        private final Set<String> definitions = new LinkedHashSet<>();
        private final Set<String> used = new LinkedHashSet<>();
        private boolean enforce;
        private Set<String> expectedDefinitions;

        private CatalogUsage(String id) {
            this.id = id;
        }

        private void add(Manifest manifest, Path path) {
            var keys = manifest.definitions().stream().map(Definition::key).collect(java.util.stream.Collectors.toSet());
            if (expectedDefinitions != null && !expectedDefinitions.equals(keys)) {
                throw new GradleException("Modules compiled different definitions for shared Thim catalog " + id + " at " + path);
            }
            expectedDefinitions = keys;
            definitions.addAll(keys);
            used.addAll(manifest.templateUsage());
            enforce |= manifest.enforce();
        }
    }

    private record ClassUsage(String className, Set<MethodReference> methods, Set<String> strings) {
        private static ClassUsage read(Path path) throws IOException {
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class file " + path);
                input.readUnsignedShort();
                input.readUnsignedShort();
                var count = input.readUnsignedShort();
                var tags = new byte[count];
                var entries = new Object[count];
                for (var index = 1; index < count; index++) {
                    var tag = input.readUnsignedByte();
                    tags[index] = (byte) tag;
                    switch (tag) {
                        case 1 -> entries[index] = input.readUTF();
                        case 3, 4 -> input.readInt();
                        case 5, 6 -> {
                            input.readLong();
                            index++;
                        }
                        case 7, 8, 16, 19, 20 -> entries[index] = input.readUnsignedShort();
                        case 9, 10, 11, 12, 17, 18 -> entries[index] = new int[]{input.readUnsignedShort(), input.readUnsignedShort()};
                        case 15 -> {
                            input.readUnsignedByte();
                            input.readUnsignedShort();
                        }
                        default -> throw new IOException("Unsupported constant pool tag " + tag + " in " + path);
                    }
                }
                input.readUnsignedShort();
                var thisClass = input.readUnsignedShort();
                var className = className(entries, thisClass);
                var methods = new HashSet<MethodReference>();
                var strings = new HashSet<String>();
                for (var index = 1; index < count; index++) {
                    if (tags[index] == 10 || tags[index] == 11) {
                        var reference = (int[]) entries[index];
                        var nameAndType = (int[]) entries[reference[1]];
                        methods.add(new MethodReference(
                                className(entries, reference[0]),
                                (String) entries[nameAndType[0]]
                        ));
                    } else if (tags[index] == 1) {
                        strings.add((String) entries[index]);
                    } else if (tags[index] == 8) {
                        strings.add((String) entries[(int) entries[index]]);
                    }
                }
                return new ClassUsage(className, methods, strings);
            }
        }

        private static String className(Object[] entries, int classIndex) {
            return (String) entries[(int) entries[classIndex]];
        }
    }
}
