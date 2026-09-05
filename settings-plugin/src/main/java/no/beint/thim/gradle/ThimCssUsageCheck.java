package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@CacheableTask
public abstract class ThimCssUsageCheck extends DefaultTask {
    private static final Set<String> USAGE_EXTENSIONS = Set.of(
            "html", "htm", "java", "kt", "kts", "js", "jsx", "mjs", "cjs", "ts", "tsx"
    );

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCssFiles();

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getUsageFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRuntimeClassFiles();

    @Input
    public abstract ListProperty<String> getRuntimeClasses();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @TaskAction
    public void checkUsage() throws IOException {
        var root = getRootDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        var definitions = definitions(root, sortedFiles(getCssFiles().getFiles()));
        var usage = usage(sortedFiles(getUsageFiles().getFiles()));
        var exact = new TreeSet<>(usage.tokens());
        exact.addAll(getRuntimeClasses().get());
        for (var file : getRuntimeClassFiles().getFiles()) {
            exact.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        }
        var used = new TreeSet<String>();
        var prefixUsed = new TreeSet<String>();
        var unused = new TreeSet<String>();
        for (var name : definitions.keySet()) {
            if (exact.contains(name)) {
                used.add(name);
            } else if (usage.prefixes().stream().anyMatch(name::startsWith)) {
                prefixUsed.add(name);
            } else {
                unused.add(name);
            }
        }

        writeReport(definitions, used, prefixUsed, unused);
        if (!unused.isEmpty()) {
            var message = new StringBuilder("Unused first-party CSS classes:\n");
            for (var name : unused) {
                message.append(" - ").append(name).append(" (")
                        .append(String.join(", ", definitions.get(name))).append(")\n");
            }
            message.append("Report: ").append(getReportFile().get().getAsFile());
            throw new GradleException(message.toString());
        }
    }

    private Map<String, Set<String>> definitions(Path root, List<File> files) throws IOException {
        var definitions = new TreeMap<String, Set<String>>();
        for (var file : files) {
            if (!file.isFile() || ignoredCssPath(file.toPath())) continue;
            var relative = relativePath(root, file.toPath());
            for (var name : classNames(Files.readString(file.toPath(), StandardCharsets.UTF_8))) {
                definitions.computeIfAbsent(name, ignored -> new TreeSet<>()).add(relative);
            }
        }
        return definitions;
    }

    private Usage usage(List<File> files) throws IOException {
        var tokens = new LinkedHashSet<String>();
        var prefixes = new LinkedHashSet<String>();
        for (var file : files) {
            if (!file.isFile() || nodeModulesPath(file.toPath()) || !USAGE_EXTENSIONS.contains(extension(file.toPath()))) {
                continue;
            }
            collectLiterals(Files.readString(file.toPath(), StandardCharsets.UTF_8), tokens, prefixes);
        }
        return new Usage(tokens, prefixes);
    }

    static Set<String> classNames(String css) {
        var names = new LinkedHashSet<String>();
        var index = 0;
        Character quote = null;
        while (index < css.length()) {
            var character = css.charAt(index);
            if (quote != null) {
                if (character == '\\' && index + 1 < css.length()) {
                    index += 2;
                } else {
                    if (character == quote) quote = null;
                    index++;
                }
                continue;
            }
            if (css.startsWith("/*", index)) {
                var end = css.indexOf("*/", index + 2);
                index = end < 0 ? css.length() : end + 2;
                continue;
            }
            if (character == '\"' || character == '\'') {
                quote = character;
                index++;
                continue;
            }
            if (character == '.' && classStart(css, index + 1)) {
                var identifier = readCssIdentifier(css, index + 1);
                if (!identifier.value().isBlank()) names.add(identifier.value());
                index = identifier.end();
                continue;
            }
            index++;
        }
        return names;
    }

    static Usage collectLiterals(String source, Set<String> tokens, Set<String> prefixes) {
        var index = 0;
        while (index < source.length()) {
            if (source.startsWith("<!--", index)) {
                var end = source.indexOf("-->", index + 4);
                index = end < 0 ? source.length() : end + 3;
                continue;
            }
            if (source.startsWith("//", index)) {
                var end = source.indexOf('\n', index + 2);
                index = end < 0 ? source.length() : end + 1;
                continue;
            }
            if (source.startsWith("/*", index)) {
                var end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                continue;
            }
            var quote = source.charAt(index);
            if (quote != '\"' && quote != '\'' && quote != '`') {
                index++;
                continue;
            }
            var literal = new StringBuilder();
            index++;
            while (index < source.length()) {
                var character = source.charAt(index);
                if (character == '\\' && index + 1 < source.length()) {
                    literal.append(character).append(source.charAt(index + 1));
                    index += 2;
                } else if (character == quote) {
                    index++;
                    break;
                } else {
                    literal.append(character);
                    index++;
                }
            }
            addLiteral(literal.toString(), tokens, prefixes);
        }
        return new Usage(tokens, prefixes);
    }

    private static void addLiteral(String literal, Set<String> tokens, Set<String> prefixes) {
        collectInterpolationLiterals(literal, tokens, prefixes);
        for (var segment : interpolationSegments(literal)) {
            for (var token : classTokens(segment)) {
                tokens.add(token);
                if (dynamicPrefix(token)) prefixes.add(token);
            }
        }
    }

    private static void collectInterpolationLiterals(String literal, Set<String> tokens, Set<String> prefixes) {
        var index = 0;
        while ((index = literal.indexOf("${", index)) >= 0) {
            var start = index + 2;
            index = start;
            var depth = 1;
            Character quote = null;
            while (index < literal.length() && depth > 0) {
                var character = literal.charAt(index);
                if (quote != null) {
                    if (character == '\\' && index + 1 < literal.length()) {
                        index += 2;
                        continue;
                    }
                    if (character == quote) quote = null;
                } else if (character == '\"' || character == '\'' || character == '`') {
                    quote = character;
                } else if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                }
                index++;
            }
            var end = depth == 0 ? index - 1 : literal.length();
            collectLiterals(literal.substring(start, end), tokens, prefixes);
        }
    }

    private static List<String> interpolationSegments(String literal) {
        var segments = new ArrayList<String>();
        var index = 0;
        var start = 0;
        while (index < literal.length()) {
            if (literal.charAt(index) != '$') {
                index++;
                continue;
            }
            segments.add(literal.substring(start, index));
            index++;
            if (index < literal.length() && literal.charAt(index) == '{') {
                var depth = 1;
                index++;
                while (index < literal.length() && depth > 0) {
                    if (literal.charAt(index) == '{') depth++;
                    if (literal.charAt(index) == '}') depth--;
                    index++;
                }
            } else {
                while (index < literal.length() &&
                        (Character.isLetterOrDigit(literal.charAt(index)) || literal.charAt(index) == '_')) {
                    index++;
                }
            }
            start = index;
        }
        segments.add(literal.substring(start));
        return segments;
    }

    static List<String> classTokens(String value) {
        var tokens = new ArrayList<String>();
        var index = 0;
        while (index < value.length()) {
            var character = value.charAt(index);
            if (!classStart(value, index)) {
                index++;
                continue;
            }
            var start = index++;
            while (index < value.length() && classPart(value.charAt(index))) index++;
            tokens.add(value.substring(start, index));
        }
        return tokens;
    }

    private static boolean dynamicPrefix(String token) {
        return token.length() >= 5 && token.endsWith("-") && token.chars().filter(character -> character == '-').count() >= 2;
    }

    private static boolean classStart(String source, int index) {
        if (index >= source.length()) return false;
        var character = source.charAt(index);
        if (character == '\\' || character == '_' || Character.isLetter(character)) return true;
        return character == '-' && index + 1 < source.length() &&
                (source.charAt(index + 1) == '_' || Character.isLetter(source.charAt(index + 1)));
    }

    private static boolean classPart(char character) {
        return Character.isLetterOrDigit(character) || character == '-' || character == '_' ||
                character == ':' || character == '[' || character == ']' || character == '(' ||
                character == ')' || character == '%' || character == '.' || character == '+' ||
                character == '/' || character == ',' || character == '#';
    }

    private static CssIdentifier readCssIdentifier(String source, int start) {
        var value = new StringBuilder();
        var index = start;
        while (index < source.length()) {
            var character = source.charAt(index);
            if (character == '\\' && index + 1 < source.length()) {
                var hex = index + 1;
                while (hex < source.length() && hex - index <= 6 && hexDigit(source.charAt(hex))) hex++;
                if (hex > index + 1) {
                    var codePoint = Integer.parseInt(source.substring(index + 1, hex), 16);
                    value.appendCodePoint(codePoint);
                    index = hex < source.length() && Character.isWhitespace(source.charAt(hex)) ? hex + 1 : hex;
                } else {
                    value.append(source.charAt(index + 1));
                    index += 2;
                }
            } else if (character == '_' || character == '-' || Character.isLetterOrDigit(character)) {
                value.append(character);
                index++;
            } else {
                break;
            }
        }
        return new CssIdentifier(value.toString(), index);
    }

    private void writeReport(
            Map<String, Set<String>> definitions,
            Set<String> used,
            Set<String> prefixUsed,
            Set<String> unused
    ) throws IOException {
        var report = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        var json = new StringBuilder();
        json.append("{\n")
                .append("  \"defined\": ").append(definitions.size()).append(",\n")
                .append("  \"used\": ").append(used.size()).append(",\n")
                .append("  \"prefixUsed\": ").append(prefixUsed.size()).append(",\n")
                .append("  \"unused\": ").append(unused.size()).append(",\n")
                .append("  \"unusedClasses\": [");
        var separator = "";
        for (var name : unused) {
            json.append(separator).append("\n    {\"name\": \"").append(json(name)).append("\", \"files\": [");
            var fileSeparator = "";
            for (var file : definitions.get(name)) {
                json.append(fileSeparator).append("\"").append(json(file)).append("\"");
                fileSeparator = ", ";
            }
            json.append("]}");
            separator = ",";
        }
        if (!unused.isEmpty()) json.append('\n').append("  ");
        json.append("]\n}\n");
        Files.writeString(report, json.toString(), StandardCharsets.UTF_8);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<File> sortedFiles(Set<File> files) {
        return files.stream().sorted(Comparator.comparing(File::getAbsolutePath)).toList();
    }

    private static String relativePath(Path root, Path file) {
        var absolute = file.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute.getFileName())
                .toString().replace(File.separatorChar, '/');
    }

    private static boolean ignoredCssPath(Path file) {
        for (var part : file) {
            var name = part.toString();
            if (name.equalsIgnoreCase("vendor") || name.equals("node_modules")) return true;
        }
        return false;
    }

    private static boolean nodeModulesPath(Path file) {
        for (var part : file) {
            if (part.toString().equals("node_modules")) return true;
        }
        return false;
    }

    private static String extension(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hexDigit(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f' ||
                character >= 'A' && character <= 'F';
    }

    record Usage(Set<String> tokens, Set<String> prefixes) {}

    private record CssIdentifier(String value, int end) {}
}
