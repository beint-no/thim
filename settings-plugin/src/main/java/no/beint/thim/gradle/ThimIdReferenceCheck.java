package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
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
import java.util.regex.Pattern;

@CacheableTask
public abstract class ThimIdReferenceCheck extends DefaultTask {
    private static final Set<String> HTML_EXTENSIONS = Set.of("html", "htm");
    private static final Set<String> JS_EXTENSIONS = Set.of("js", "jsx", "mjs", "cjs", "ts", "tsx");
    private static final Set<String> JVM_EXTENSIONS = Set.of("kt", "kts", "java");

    private static final Pattern SIMPLE_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern SIMPLE_HASH_ID = Pattern.compile("#([A-Za-z0-9_-]+)");
    private static final Pattern HX_STATUS_TARGET = Pattern.compile("(?i)(?:^|[\\s,;])target\\s*:\\s*([^,;]+)");
    private static final Pattern HX_STATUS_NEXT_DIRECTIVE = Pattern.compile("\\s+[A-Za-z][A-Za-z0-9_-]*\\s*:");

    private static final Pattern JS_DOT_ID_ASSIGN = Pattern.compile(
            "\\.id\\s*=\\s*(['\"`])([^'\"`\\r\\n]+)\\1(?:\\s*\\+)?"
    );
    private static final Pattern JS_SET_ATTR_ID = Pattern.compile(
            "setAttribute\\s*\\(\\s*['\"]id['\"]\\s*,\\s*(['\"`])([^'\"`\\r\\n]+)\\1(?:\\s*\\+)?"
    );
    private static final Pattern HTML_ATTR_ID_IN_STRING = Pattern.compile(
            "(?:^|[\\s<])id\\s*=\\s*(?:\\\\)?(['\"])([^'\"<>\\r\\n]+?)(?:\\\\)?\\1"
    );
    private static final Pattern KOTLIN_ID_ARG = Pattern.compile(
            "\\bid\\s*=\\s*\"([^\"\\r\\n]+)\""
    );
    private static final Pattern QUOTED_LITERAL = Pattern.compile("(['\"])([^'\"\\r\\n]+)\\1");

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getUsageFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModuleManifestFiles();

    @Input
    public abstract ListProperty<String> getRuntimeIds();

    @Input
    public abstract Property<Boolean> getFailOnBrokenIdReferences();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @TaskAction
    public void checkReferences() throws IOException {
        var root = getRootDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        var manifests = new ArrayList<ThimCssModuleManifest.ModuleManifest>();
        for (var file : ThimCssUsageCheck.sortedFiles(getModuleManifestFiles().getFiles())) {
            if (file.isFile()) manifests.add(ThimCssModuleManifest.read(file.toPath()));
        }

        if (!getFailOnBrokenIdReferences().get()) {
            writeReport(0, 0, List.of());
            return;
        }

        var declaredIds = new LinkedHashSet<String>(getRuntimeIds().get());
        var declaredPrefixes = new LinkedHashSet<String>();
        for (var manifest : manifests) {
            declaredIds.addAll(manifest.runtimeIds());
        }

        var files = ThimCssUsageCheck.sortedFiles(getUsageFiles().getFiles());
        var references = new ArrayList<IdReference>();

        for (var file : files) {
            var path = file.toPath();
            if (!file.isFile() || ThimCssUsageCheck.nodeModulesPath(path)) continue;
            var ext = ThimCssUsageCheck.extension(path);
            var relative = ThimCssUsageCheck.relativePath(root, path);
            var content = Files.readString(path, StandardCharsets.UTF_8);

            if (HTML_EXTENSIONS.contains(ext)) {
                var scan = HtmlTagScanner.scan(content);
                collectHtmlDeclaredIds(scan, declaredIds, declaredPrefixes);
                if (!ThimCssUsageCheck.ignoredCssPath(path) && shouldEnforce(relative, manifests)) {
                    collectHtmlIdReferences(relative, scan, references);
                }
            } else if (JS_EXTENSIONS.contains(ext)) {
                collectJsDeclaredIds(content, declaredIds, declaredPrefixes);
                if (!ThimCssUsageCheck.ignoredCssPath(path) && shouldEnforce(relative, manifests)) {
                    collectJsIdReferences(relative, content, 1, references);
                }
            } else if (JVM_EXTENSIONS.contains(ext)) {
                collectJvmDeclaredIds(content, declaredIds, declaredPrefixes);
            }
        }

        var broken = new ArrayList<IdReference>();
        for (var reference : references) {
            if (!declaredIds.contains(reference.id()) &&
                    declaredPrefixes.stream().noneMatch(reference.id()::startsWith)) {
                broken.add(reference);
            }
        }
        broken.sort(Comparator.comparing(IdReference::file)
                .thenComparingInt(IdReference::line)
                .thenComparing(IdReference::id));

        writeReport(declaredIds.size(), references.size(), broken);
        if (!broken.isEmpty()) {
            var message = new StringBuilder("Broken #id references:\n");
            for (var item : broken) {
                message.append(" - ").append(item.file()).append(':').append(item.line())
                        .append(' ').append(item.expression()).append(" -> #").append(item.id()).append('\n');
            }
            message.append("Report: ").append(getReportFile().get().getAsFile());
            throw new GradleException(message.toString());
        }
    }

    private static boolean shouldEnforce(String relativePath, List<ThimCssModuleManifest.ModuleManifest> manifests) {
        var owner = ThimCssModuleManifest.findOwner(relativePath, manifests);
        return owner == null || owner.failOnBrokenIdReferences();
    }

    static void collectHtmlDeclaredIds(
            HtmlTagScanner.ScanResult scan,
            Set<String> ids,
            Set<String> prefixes
    ) {
        for (var tag : scan.tags()) {
            var thId = tag.attributes().get("th:id");
            if (thId == null) {
                var idAttr = tag.attributes().get("id");
                if (idAttr != null && idAttr.value() != null) {
                    var value = idAttr.value().trim();
                    if (!value.isEmpty() && !containsTemplateExpression(value)) {
                        ids.add(value);
                    }
                }
            } else if (thId.value() != null) {
                collectDynamicIdExpression(thId.value(), ids, prefixes);
            }

            var thField = tag.attributes().get("th:field");
            if (thField != null && thField.value() != null) {
                var fieldName = ThimCssUsageCheck.stripFieldExpression(thField.value());
                if (!fieldName.isEmpty()) {
                    var typeAttr = tag.attributes().get("type");
                    var valueAttr = tag.attributes().get("value");
                    if (tag.name().equals("input") &&
                            typeAttr != null && "radio".equalsIgnoreCase(typeAttr.value()) &&
                            valueAttr != null && valueAttr.value() != null) {
                        ids.add(fieldName + "." + valueAttr.value());
                    }
                    ids.add(fieldName);
                }
            }
        }
        for (var script : scan.scripts()) {
            collectJsDeclaredIds(script.body(), ids, prefixes);
        }
    }

    static void collectDynamicIdExpression(String expression, Set<String> ids, Set<String> prefixes) {
        var trimmed = expression.trim();
        if (trimmed.isEmpty()) return;

        // Pipe interpolation: |foo-${x}| or |exact|
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() >= 2) {
            var inner = trimmed.substring(1, trimmed.length() - 1);
            var exprIdx = firstExpressionIndex(inner);
            if (exprIdx < 0) {
                if (!inner.isBlank()) ids.add(inner.trim());
            } else if (exprIdx > 0) {
                prefixes.add(inner.substring(0, exprIdx));
            }
            return;
        }

        // Plain single or double quoted literal: 'my-id' or "my-id"
        if ((trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.indexOf('\'', 1) == trimmed.length() - 1) ||
                (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.indexOf('"', 1) == trimmed.length() - 1)) {
            var value = trimmed.substring(1, trimmed.length() - 1).trim();
            if (!value.isEmpty()) ids.add(value);
            return;
        }

        // Unwrapped interpolation: foo-${x}
        var exprIdx = firstExpressionIndex(trimmed);
        if (exprIdx > 0 && !trimmed.startsWith("'") && !trimmed.startsWith("\"")) {
            var prefix = trimmed.substring(0, exprIdx).trim();
            if (!prefix.isEmpty() && SIMPLE_ID.matcher(prefix).matches()) {
                prefixes.add(prefix);
            }
        }

        // Concatenation or ternary literals: 'foo-' + ${id} or ${cond} ? 'a' : 'b'
        var matcher = QUOTED_LITERAL.matcher(trimmed);
        while (matcher.find()) {
            var value = matcher.group(2);
            if (value.isEmpty()) continue;
            var after = trimmed.substring(matcher.end()).trim();
            if (after.startsWith("+")) {
                prefixes.add(value);
            } else if (SIMPLE_ID.matcher(value).matches()) {
                ids.add(value);
            }
        }
    }

    static void collectJsDeclaredIds(String js, Set<String> ids, Set<String> prefixes) {
        var stripped = stripCodeComments(js);
        collectJsPattern(JS_DOT_ID_ASSIGN, stripped, ids, prefixes);
        collectJsPattern(JS_SET_ATTR_ID, stripped, ids, prefixes);
        collectHtmlIdsInStrings(stripped, ids, prefixes);
    }

    private static void collectJsPattern(Pattern pattern, String code, Set<String> ids, Set<String> prefixes) {
        var matcher = pattern.matcher(code);
        while (matcher.find()) {
            var full = matcher.group(0);
            var quote = matcher.group(1);
            var value = matcher.group(2);
            if (quote.equals("`") && value.contains("${")) {
                var prefix = value.substring(0, value.indexOf("${"));
                if (!prefix.isEmpty()) prefixes.add(prefix);
            } else if (full.endsWith("+")) {
                if (!value.isEmpty()) prefixes.add(value);
            } else if (!value.isEmpty()) {
                ids.add(value);
            }
        }
    }

    static void collectJvmDeclaredIds(String source, Set<String> ids, Set<String> prefixes) {
        var stripped = stripCodeComments(source);
        collectHtmlIdsInStrings(stripped, ids, prefixes);
        var matcher = KOTLIN_ID_ARG.matcher(stripped);
        while (matcher.find()) {
            var value = matcher.group(1);
            var dollar = value.indexOf('$');
            if (dollar < 0) {
                if (SIMPLE_ID.matcher(value).matches()) ids.add(value);
            } else if (dollar > 0) {
                var prefix = value.substring(0, dollar);
                if (SIMPLE_ID.matcher(prefix).matches()) prefixes.add(prefix);
            }
        }
    }

    private static void collectHtmlIdsInStrings(String source, Set<String> ids, Set<String> prefixes) {
        var matcher = HTML_ATTR_ID_IN_STRING.matcher(source);
        while (matcher.find()) {
            var value = matcher.group(2).trim();
            if (value.isEmpty()) continue;
            var dollar = value.indexOf('$');
            if (dollar < 0) {
                if (SIMPLE_ID.matcher(value).matches()) ids.add(value);
            } else if (dollar > 0) {
                var prefix = value.substring(0, dollar);
                if (SIMPLE_ID.matcher(prefix).matches()) prefixes.add(prefix);
            }
        }
    }

    static void collectHtmlIdReferences(String file, HtmlTagScanner.ScanResult scan, List<IdReference> references) {
        for (var tag : scan.tags()) {
            var attrs = tag.attributes();
            checkSingleHashAttribute(file, attrs, "hx-target", references);
            checkCommaHashAttribute(file, attrs, "hx-include", references);
            checkCommaHashAttribute(file, attrs, "hx-indicator", references);
            checkPopoverTarget(file, attrs, references);
            for (var entry : attrs.entrySet()) {
                var name = entry.getKey();
                if (name.startsWith("hx-status:") && !attrs.containsKey("th:" + name)) {
                    var attr = entry.getValue();
                    if (attr.value() != null) {
                        extractHxStatusTargets(file, name, attr.value(), attr.line(), references);
                    }
                }
            }
        }
        for (var script : scan.scripts()) {
            collectJsIdReferences(file, script.body(), script.startLine(), references);
        }
    }

    private static void checkSingleHashAttribute(
            String file,
            java.util.Map<String, HtmlTagScanner.Attribute> attrs,
            String name,
            List<IdReference> references
    ) {
        if (attrs.containsKey("th:" + name)) return;
        var attr = attrs.get(name);
        if (attr == null || attr.value() == null) return;
        var trimmed = attr.value().trim();
        var matcher = SIMPLE_HASH_ID.matcher(trimmed);
        if (matcher.matches()) {
            references.add(new IdReference(file, attr.line(), name + "=\"" + trimmed + "\"", matcher.group(1)));
        }
    }

    private static void checkCommaHashAttribute(
            String file,
            java.util.Map<String, HtmlTagScanner.Attribute> attrs,
            String name,
            List<IdReference> references
    ) {
        if (attrs.containsKey("th:" + name)) return;
        var attr = attrs.get(name);
        if (attr == null || attr.value() == null) return;
        for (var part : attr.value().split(",")) {
            var trimmed = part.trim();
            var matcher = SIMPLE_HASH_ID.matcher(trimmed);
            if (matcher.matches()) {
                references.add(new IdReference(file, attr.line(), name + "=\"" + attr.value() + "\"", matcher.group(1)));
            }
        }
    }

    private static void checkPopoverTarget(
            String file,
            java.util.Map<String, HtmlTagScanner.Attribute> attrs,
            List<IdReference> references
    ) {
        if (attrs.containsKey("th:popovertarget")) return;
        var attr = attrs.get("popovertarget");
        if (attr == null || attr.value() == null) return;
        var trimmed = attr.value().trim();
        if (SIMPLE_ID.matcher(trimmed).matches()) {
            references.add(new IdReference(file, attr.line(), "popovertarget=\"" + trimmed + "\"", trimmed));
        }
    }

    private static void extractHxStatusTargets(
            String file,
            String attrName,
            String attrValue,
            int line,
            List<IdReference> references
    ) {
        var matcher = HX_STATUS_TARGET.matcher(attrValue);
        while (matcher.find()) {
            var candidate = matcher.group(1);
            var nextDir = HX_STATUS_NEXT_DIRECTIVE.matcher(candidate);
            if (nextDir.find()) {
                candidate = candidate.substring(0, nextDir.start());
            }
            candidate = candidate.trim();
            var idMatcher = SIMPLE_HASH_ID.matcher(candidate);
            if (idMatcher.matches()) {
                references.add(new IdReference(file, line, attrName + "=\"" + attrValue + "\"", idMatcher.group(1)));
            }
        }
    }

    static void collectJsIdReferences(String file, String js, int startLine, List<IdReference> references) {
        var length = js.length();
        var index = 0;
        var line = startLine;
        Character quote = null;
        while (index < length) {
            var ch = js.charAt(index);
            if (ch == '\n') {
                line++;
                index++;
                continue;
            }
            if (quote != null) {
                if (ch == '\\' && index + 1 < length) {
                    if (js.charAt(index + 1) == '\n') line++;
                    index += 2;
                } else {
                    if (ch == quote) quote = null;
                    index++;
                }
                continue;
            }
            if (js.startsWith("//", index)) {
                var end = js.indexOf('\n', index + 2);
                index = end < 0 ? length : end;
                continue;
            }
            if (js.startsWith("/*", index)) {
                var end = js.indexOf("*/", index + 2);
                var next = end < 0 ? length : end + 2;
                line += countNewlines(js, index, next);
                index = next;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                quote = ch;
                index++;
                continue;
            }

            if (ch == 'd' && js.startsWith("document.getElementById", index) &&
                    (index == 0 || !isJsIdentChar(js.charAt(index - 1)))) {
                var afterName = index + "document.getElementById".length();
                var arg = parseSingleSimpleStringArg(js, afterName);
                if (arg != null && SIMPLE_ID.matcher(arg.value()).matches()) {
                    references.add(new IdReference(
                            file, line, "document.getElementById(\"" + arg.value() + "\")", arg.value()
                    ));
                    line += countNewlines(js, index, arg.end());
                    index = arg.end();
                    continue;
                }
            } else if (ch == 'q' && js.startsWith("querySelector", index) &&
                    (index == 0 || !isJsIdentChar(js.charAt(index - 1)))) {
                var afterName = index + "querySelector".length();
                if (afterName < length && !isJsIdentChar(js.charAt(afterName))) {
                    var arg = parseSingleSimpleStringArg(js, afterName);
                    if (arg != null) {
                        var idMatcher = SIMPLE_HASH_ID.matcher(arg.value().trim());
                        if (idMatcher.matches()) {
                            references.add(new IdReference(
                                    file, line, "querySelector(\"#" + idMatcher.group(1) + "\")", idMatcher.group(1)
                            ));
                            line += countNewlines(js, index, arg.end());
                            index = arg.end();
                            continue;
                        }
                    }
                }
            }
            index++;
        }
    }

    private static ParsedArg parseSingleSimpleStringArg(String js, int start) {
        var pos = skipSpaces(js, start);
        if (pos >= js.length() || js.charAt(pos) != '(') return null;
        pos = skipSpaces(js, pos + 1);
        if (pos >= js.length()) return null;
        var quote = js.charAt(pos);
        if (quote != '"' && quote != '\'' && quote != '`') return null;
        pos++;
        var valueStart = pos;
        while (pos < js.length()) {
            var ch = js.charAt(pos);
            if (ch == '\\' && pos + 1 < js.length()) {
                pos += 2;
                continue;
            }
            if (ch == '\n' || ch == '\r') return null;
            if (ch == quote) break;
            pos++;
        }
        if (pos >= js.length() || js.charAt(pos) != quote) return null;
        var value = js.substring(valueStart, pos);
        if (quote == '`' && value.contains("${")) return null;
        pos = skipSpaces(js, pos + 1);
        if (pos >= js.length() || js.charAt(pos) != ')') return null;
        return new ParsedArg(value, pos + 1);
    }

    private static int skipSpaces(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static boolean isJsIdentChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private static boolean containsTemplateExpression(String value) {
        return value.contains("${") || value.contains("#{") || value.contains("*{") || value.contains("@{");
    }

    private static int firstExpressionIndex(String value) {
        var min = -1;
        for (var marker : List.of("${", "#{", "*{", "@{")) {
            var idx = value.indexOf(marker);
            if (idx >= 0 && (min < 0 || idx < min)) min = idx;
        }
        return min;
    }

    private static String stripCodeComments(String source) {
        var out = new StringBuilder(source.length());
        var length = source.length();
        var index = 0;
        Character quote = null;
        while (index < length) {
            var ch = source.charAt(index);
            if (quote != null) {
                out.append(ch);
                if (ch == '\\' && index + 1 < length) {
                    out.append(source.charAt(index + 1));
                    index += 2;
                } else {
                    if (ch == quote) quote = null;
                    index++;
                }
            } else if (source.startsWith("//", index)) {
                var end = source.indexOf('\n', index + 2);
                index = end < 0 ? length : end;
            } else if (source.startsWith("/*", index)) {
                var end = source.indexOf("*/", index + 2);
                index = end < 0 ? length : end + 2;
            } else {
                if (ch == '"' || ch == '\'' || ch == '`') quote = ch;
                out.append(ch);
                index++;
            }
        }
        return out.toString();
    }

    private static int countNewlines(String text, int start, int end) {
        var count = 0;
        for (var i = start; i < end; i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private void writeReport(int declaredCount, int referenceCount, List<IdReference> broken) throws IOException {
        var report = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        var json = new StringBuilder();
        json.append("{\n")
                .append("  \"status\": \"").append(broken.isEmpty() ? "valid" : "invalid").append("\",\n")
                .append("  \"declaredIds\": ").append(declaredCount).append(",\n")
                .append("  \"checkedReferences\": ").append(referenceCount).append(",\n")
                .append("  \"brokenReferences\": [");
        var separator = "";
        for (var item : broken) {
            json.append(separator).append("\n    {\"file\": \"").append(json(item.file()))
                    .append("\", \"line\": ").append(item.line())
                    .append(", \"expression\": \"").append(json(item.expression()))
                    .append("\", \"id\": \"").append(json(item.id())).append("\"}");
            separator = ",";
        }
        if (!broken.isEmpty()) json.append('\n').append("  ");
        json.append("]\n}\n");
        Files.writeString(report, json.toString(), StandardCharsets.UTF_8);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record IdReference(String file, int line, String expression, String id) {}

    private record ParsedArg(String value, int end) {}
}
