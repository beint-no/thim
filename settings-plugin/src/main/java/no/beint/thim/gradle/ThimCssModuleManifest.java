package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@CacheableTask
public abstract class ThimCssModuleManifest extends DefaultTask {
    private static final String HEADER = "thim-css-module-manifest\t1";

    @Input
    public abstract Property<String> getModulePath();

    @Input
    public abstract Property<String> getModuleRelativeDir();

    @Input
    public abstract Property<Boolean> getFailOnUnusedCss();

    @Input
    public abstract Property<Boolean> getFailOnUnusedCssVariables();

    @Input
    public abstract Property<Boolean> getFailOnUnstyledCss();

    @Input
    public abstract Property<Boolean> getFailOnBrokenIdReferences();

    @Input
    public abstract ListProperty<String> getRuntimeCssClasses();

    @Input
    public abstract ListProperty<String> getRuntimeIds();

    @Input
    public abstract ListProperty<String> getIgnoredCssVariablePrefixes();

    @Input
    public abstract ListProperty<String> getCheckedCssClassPrefixes();

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getTemplateFiles();

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModuleCssFiles();

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExternalCssFiles();

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void writeManifest() throws IOException {
        var root = getRootDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        var lines = new ArrayList<String>();
        lines.add(HEADER);
        lines.add("module\t" + getModulePath().get());
        lines.add("moduleDir\t" + getModuleRelativeDir().get());
        lines.add("failOnUnusedCss\t" + getFailOnUnusedCss().get());
        lines.add("failOnUnusedCssVariables\t" + getFailOnUnusedCssVariables().get());
        lines.add("failOnUnstyledCss\t" + getFailOnUnstyledCss().get());
        lines.add("failOnBrokenIdReferences\t" + getFailOnBrokenIdReferences().get());

        for (var value : sortedDistinct(getRuntimeCssClasses().get())) {
            lines.add("runtimeClass\t" + value);
        }
        for (var value : sortedDistinct(getRuntimeIds().get())) {
            lines.add("runtimeId\t" + value);
        }
        for (var value : sortedDistinct(getIgnoredCssVariablePrefixes().get())) {
            lines.add("ignoredVarPrefix\t" + value);
        }
        var checkedPrefixes = sortedDistinct(getCheckedCssClassPrefixes().get());
        for (var value : checkedPrefixes) {
            lines.add("checkedClassPrefix\t" + value);
        }

        var checkReverse = getFailOnUnstyledCss().get() || !checkedPrefixes.isEmpty();
        var localCssClasses = new TreeSet<String>();
        var localCssFiles = new TreeSet<String>();
        for (var file : collectCssFiles(getModuleCssFiles().getFiles(), true)) {
            localCssFiles.add(relativePath(root, file));
            if (checkReverse) {
                var parsed = ThimCssUsageCheck.parseCss(Files.readString(file, StandardCharsets.UTF_8));
                localCssClasses.addAll(parsed.classes());
            }
        }
        for (var file : localCssFiles) {
            lines.add("localCssFile\t" + file);
        }
        for (var name : localCssClasses) {
            lines.add("localCssClass\t" + name);
        }

        var externalCssClasses = new TreeSet<String>();
        var externalCssVarRefs = new TreeSet<String>();
        var externalCssVarPrefixes = new TreeSet<String>();
        for (var file : collectCssFiles(getExternalCssFiles().getFiles(), false)) {
            var parsed = ThimCssUsageCheck.parseCss(Files.readString(file, StandardCharsets.UTF_8));
            if (checkReverse) {
                externalCssClasses.addAll(parsed.classes());
            }
            externalCssVarRefs.addAll(parsed.varRefs());
            externalCssVarPrefixes.addAll(parsed.varPrefixes());
        }
        for (var name : externalCssClasses) {
            lines.add("externalCssClass\t" + name);
        }
        for (var name : externalCssVarRefs) {
            lines.add("externalCssVarRef\t" + name);
        }
        for (var prefix : externalCssVarPrefixes) {
            lines.add("externalCssVarPrefix\t" + prefix);
        }

        if (checkReverse) {
            var templateClasses = new TreeSet<String>();
            var files = getTemplateFiles().getFiles().stream()
                    .filter(File::isFile)
                    .sorted(Comparator.comparing(File::getAbsolutePath))
                    .toList();
            for (var file : files) {
                var relative = relativePath(root, file.toPath());
                var scan = HtmlTagScanner.scan(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                for (var tag : scan.tags()) {
                    if (tag.attributes().containsKey("th:class")) continue;
                    var classAttr = tag.attributes().get("class");
                    if (classAttr == null || classAttr.value() == null) continue;
                    for (var token : classAttr.value().split("\\s+")) {
                        if (token.isEmpty() || isDynamicExpression(token)) continue;
                        templateClasses.add("templateClass\t" + token + "\t" + relative + "\t" + classAttr.line());
                    }
                }
            }
            lines.addAll(templateClasses);
        }

        var output = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static boolean isDynamicExpression(String token) {
        return token.contains("${") || token.contains("#{") || token.contains("*{") ||
                token.contains("@{") || token.contains("|");
    }

    private static List<String> sortedDistinct(List<String> values) {
        var result = new TreeSet<String>();
        for (var value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        return List.copyOf(result);
    }

    private static List<Path> collectCssFiles(Set<File> inputs, boolean skipVendor) throws IOException {
        var paths = new ArrayList<Path>();
        for (var input : inputs) {
            if (!input.exists()) continue;
            if (input.isFile()) {
                var path = input.toPath();
                if (!skipVendor || !ThimCssUsageCheck.ignoredCssPath(path)) {
                    paths.add(path);
                }
            } else if (input.isDirectory()) {
                try (var stream = Files.walk(input.toPath())) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".css"))
                            .filter(path -> !skipVendor || !ThimCssUsageCheck.ignoredCssPath(path))
                            .forEach(paths::add);
                }
            }
        }
        paths.sort(Comparator.comparing(Path::toAbsolutePath));
        return paths;
    }

    private static String relativePath(Path root, Path file) {
        var absolute = file.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute.getFileName())
                .toString().replace(File.separatorChar, '/');
    }

    record TemplateClassUse(String className, String file, int line) {}

    record ModuleManifest(
            String modulePath,
            String moduleDir,
            boolean failOnUnusedCss,
            boolean failOnUnusedCssVariables,
            boolean failOnUnstyledCss,
            boolean failOnBrokenIdReferences,
            Set<String> runtimeCssClasses,
            Set<String> runtimeIds,
            Set<String> ignoredCssVariablePrefixes,
            Set<String> checkedCssClassPrefixes,
            Set<String> localCssFiles,
            Set<String> localCssClasses,
            Set<String> externalCssClasses,
            Set<String> externalCssVarRefs,
            Set<String> externalCssVarPrefixes,
            List<TemplateClassUse> templateClasses
    ) {
        boolean owns(String relativePath) {
            if (moduleDir.isEmpty()) return true;
            return relativePath.equals(moduleDir) || relativePath.startsWith(moduleDir + "/");
        }
    }

    static ModuleManifest read(Path path) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw new IOException("Unsupported Thim CSS module manifest: " + path);
        }
        var modulePath = ":";
        var moduleDir = "";
        var failOnUnusedCss = true;
        var failOnUnusedCssVariables = true;
        var failOnUnstyledCss = false;
        var failOnBrokenIdReferences = true;
        var runtimeClasses = new LinkedHashSet<String>();
        var runtimeIds = new LinkedHashSet<String>();
        var ignoredVarPrefixes = new LinkedHashSet<String>();
        var checkedClassPrefixes = new LinkedHashSet<String>();
        var localCssFiles = new LinkedHashSet<String>();
        var localCssClasses = new LinkedHashSet<String>();
        var externalCssClasses = new LinkedHashSet<String>();
        var externalCssVarRefs = new LinkedHashSet<String>();
        var externalCssVarPrefixes = new LinkedHashSet<String>();
        var templateClasses = new ArrayList<TemplateClassUse>();

        for (var line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            var parts = line.split("\t", -1);
            switch (parts[0]) {
                case "module" -> modulePath = parts[1];
                case "moduleDir" -> moduleDir = parts[1];
                case "failOnUnusedCss" -> failOnUnusedCss = Boolean.parseBoolean(parts[1]);
                case "failOnUnusedCssVariables" -> failOnUnusedCssVariables = Boolean.parseBoolean(parts[1]);
                case "failOnUnstyledCss" -> failOnUnstyledCss = Boolean.parseBoolean(parts[1]);
                case "failOnBrokenIdReferences" -> failOnBrokenIdReferences = Boolean.parseBoolean(parts[1]);
                case "runtimeClass" -> runtimeClasses.add(parts[1]);
                case "runtimeId" -> runtimeIds.add(parts[1]);
                case "ignoredVarPrefix" -> ignoredVarPrefixes.add(parts[1]);
                case "checkedClassPrefix" -> checkedClassPrefixes.add(parts[1]);
                case "localCssFile" -> localCssFiles.add(parts[1]);
                case "localCssClass" -> localCssClasses.add(parts[1]);
                case "externalCssClass" -> externalCssClasses.add(parts[1]);
                case "externalCssVarRef" -> externalCssVarRefs.add(parts[1]);
                case "externalCssVarPrefix" -> externalCssVarPrefixes.add(parts[1]);
                case "templateClass" -> templateClasses.add(
                        new TemplateClassUse(parts[1], parts[2], Integer.parseInt(parts[3]))
                );
                default -> {}
            }
        }
        return new ModuleManifest(
                modulePath,
                moduleDir,
                failOnUnusedCss,
                failOnUnusedCssVariables,
                failOnUnstyledCss,
                failOnBrokenIdReferences,
                runtimeClasses,
                runtimeIds,
                ignoredVarPrefixes,
                checkedClassPrefixes,
                localCssFiles,
                localCssClasses,
                externalCssClasses,
                externalCssVarRefs,
                externalCssVarPrefixes,
                templateClasses
        );
    }

    static ModuleManifest findOwner(String relativePath, List<ModuleManifest> manifests) {
        ModuleManifest best = null;
        for (var manifest : manifests) {
            if (manifest.localCssFiles().contains(relativePath)) return manifest;
            if (manifest.owns(relativePath)) {
                if (best == null || manifest.moduleDir().length() > best.moduleDir().length()) {
                    best = manifest;
                }
            }
        }
        return best;
    }
}
