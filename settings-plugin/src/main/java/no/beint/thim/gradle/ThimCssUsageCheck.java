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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@CacheableTask
public abstract class ThimCssUsageCheck extends DefaultTask {
    static final List<String> DEFAULT_IGNORED_CSS_VARIABLE_PREFIXES = List.of(
            "--wa-", "--sl-", "--adyen-", "--tw-", "--bs-"
    );

    private static final Set<String> USAGE_EXTENSIONS = Set.of(
            "html", "htm", "java", "kt", "kts", "js", "jsx", "mjs", "cjs", "ts", "tsx"
    );
    private static final Set<String> DECLARATION_ONLY_AT_RULES = Set.of(
            "@font-face", "@page", "@counter-style", "@font-palette-values", "@position-try"
    );

    @InputFiles
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

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModuleManifestFiles();

    @Input
    public abstract ListProperty<String> getRuntimeClasses();

    @Input
    public abstract ListProperty<String> getRuntimeIds();

    @Input
    public abstract Property<Boolean> getFailOnUnusedCssVariables();

    @Input
    public abstract ListProperty<String> getIgnoredCssVariablePrefixes();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @TaskAction
    public void checkUsage() throws IOException {
        var root = getRootDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        var manifests = readManifests(getModuleManifestFiles().getFiles());
        var cssFiles = sortedFiles(getCssFiles().getFiles()).stream()
                .filter(file -> file.isFile() && !ignoredCssPath(file.toPath()))
                .toList();
        var hasReverseChecks = manifests.stream()
                .anyMatch(m -> m.failOnUnstyledCss() || !m.checkedCssClassPrefixes().isEmpty());

        if (cssFiles.isEmpty() && !hasReverseChecks) {
            writeReport(Map.of(), Set.of(), Set.of(), Set.of(), Map.of(), Set.of(), Map.of(), Set.of(), List.of());
            return;
        }

        var classDefinitions = new TreeMap<String, Set<String>>();
        var idDefinitions = new TreeMap<String, Set<String>>();
        var varDefinitions = new TreeMap<String, Set<String>>();
        var enforcedVarDefinitions = new TreeMap<String, Set<String>>();
        var cssVarRefs = new LinkedHashSet<String>();
        var cssVarPrefixes = new LinkedHashSet<String>();
        var defaultIgnoredVarPrefixes = getIgnoredCssVariablePrefixes().get();
        var checkVariablesGlobally = getFailOnUnusedCssVariables().get();

        for (var file : cssFiles) {
            var relative = relativePath(root, file.toPath());
            var parsed = parseCss(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (var name : parsed.classes()) {
                classDefinitions.computeIfAbsent(name, ignored -> new TreeSet<>()).add(relative);
            }
            for (var id : parsed.ids()) {
                idDefinitions.computeIfAbsent(id, ignored -> new TreeSet<>()).add(relative);
            }
            cssVarRefs.addAll(parsed.varRefs());
            cssVarPrefixes.addAll(parsed.varPrefixes());

            var owner = ThimCssModuleManifest.findOwner(relative, manifests);
            var checkVarsForFile = checkVariablesGlobally && (owner == null || owner.failOnUnusedCssVariables());
            var ignoredPrefixes = owner != null ? owner.ignoredCssVariablePrefixes() : defaultIgnoredVarPrefixes;
            for (var variable : parsed.variables()) {
                if (matchesAnyPrefix(variable, ignoredPrefixes)) continue;
                varDefinitions.computeIfAbsent(variable, ignored -> new TreeSet<>()).add(relative);
                if (checkVarsForFile) {
                    enforcedVarDefinitions.computeIfAbsent(variable, ignored -> new TreeSet<>()).add(relative);
                }
            }
        }

        for (var manifest : manifests) {
            cssVarRefs.addAll(manifest.externalCssVarRefs());
            cssVarPrefixes.addAll(manifest.externalCssVarPrefixes());
        }

        var usage = scanUsage(sortedFiles(getUsageFiles().getFiles()));

        var exactClasses = new TreeSet<>(usage.tokens());
        exactClasses.addAll(getRuntimeClasses().get());
        for (var file : getRuntimeClassFiles().getFiles()) {
            if (file.isFile()) exactClasses.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        }
        var allRuntimeClasses = new TreeSet<>(getRuntimeClasses().get());
        for (var file : getRuntimeClassFiles().getFiles()) {
            if (file.isFile()) allRuntimeClasses.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        }
        var exactIds = new TreeSet<>(usage.idTokens());
        exactIds.addAll(usage.tokens());
        exactIds.addAll(getRuntimeIds().get());
        for (var manifest : manifests) {
            exactClasses.addAll(manifest.runtimeCssClasses());
            allRuntimeClasses.addAll(manifest.runtimeCssClasses());
            exactIds.addAll(manifest.runtimeIds());
        }

        var usedClasses = new TreeSet<String>();
        var prefixUsedClasses = new TreeSet<String>();
        var unusedClasses = new TreeSet<String>();
        for (var name : classDefinitions.keySet()) {
            if (exactClasses.contains(name)) {
                usedClasses.add(name);
            } else if (usage.prefixes().stream().anyMatch(name::startsWith)) {
                prefixUsedClasses.add(name);
            } else {
                unusedClasses.add(name);
            }
        }

        var unusedIds = new TreeSet<String>();
        for (var id : idDefinitions.keySet()) {
            if (!exactIds.contains(id) &&
                    usage.idPrefixes().stream().noneMatch(id::startsWith) &&
                    usage.prefixes().stream().noneMatch(id::startsWith)) {
                unusedIds.add(id);
            }
        }

        var allVarRefs = new LinkedHashSet<>(cssVarRefs);
        allVarRefs.addAll(usage.varRefs());
        var allVarPrefixes = new LinkedHashSet<>(cssVarPrefixes);
        allVarPrefixes.addAll(usage.varPrefixes());
        var unusedVariables = new TreeSet<String>();
        for (var variable : enforcedVarDefinitions.keySet()) {
            if (!allVarRefs.contains(variable) && allVarPrefixes.stream().noneMatch(variable::startsWith)) {
                unusedVariables.add(variable);
            }
        }

        var unstyledClasses = findUnstyledClasses(classDefinitions.keySet(), allRuntimeClasses, manifests);

        writeReport(
                classDefinitions, usedClasses, prefixUsedClasses, unusedClasses,
                idDefinitions, unusedIds,
                varDefinitions, unusedVariables,
                unstyledClasses
        );

        if (!unusedClasses.isEmpty() || !unusedIds.isEmpty() || !unusedVariables.isEmpty() || !unstyledClasses.isEmpty()) {
            var message = new StringBuilder();
            if (!unusedClasses.isEmpty()) {
                message.append("Unused first-party CSS classes:\n");
                for (var name : unusedClasses) {
                    message.append(" - ").append(name).append(" (")
                            .append(String.join(", ", classDefinitions.get(name))).append(")\n");
                }
            }
            if (!unusedIds.isEmpty()) {
                message.append("Unused first-party CSS #id selectors:\n");
                for (var id : unusedIds) {
                    message.append(" - #").append(id).append(" (")
                            .append(String.join(", ", idDefinitions.get(id))).append(")\n");
                }
            }
            if (!unusedVariables.isEmpty()) {
                message.append("Unused first-party CSS custom properties:\n");
                for (var variable : unusedVariables) {
                    message.append(" - ").append(variable).append(" (")
                            .append(String.join(", ", enforcedVarDefinitions.get(variable))).append(")\n");
                }
            }
            if (!unstyledClasses.isEmpty()) {
                message.append("Unstyled HTML classes:\n");
                for (var missing : unstyledClasses) {
                    message.append(" - ").append(missing.className())
                            .append(" (").append(missing.file()).append(':').append(missing.line()).append(")\n");
                }
            }
            message.append("Report: ").append(getReportFile().get().getAsFile());
            throw new GradleException(message.toString());
        }
    }

    private List<UnstyledClass> findUnstyledClasses(
            Set<String> buildFirstPartyClasses,
            Set<String> allRuntimeClasses,
            List<ThimCssModuleManifest.ModuleManifest> manifests
    ) {
        var allKnownFirstParty = new TreeSet<>(buildFirstPartyClasses);
        for (var manifest : manifests) {
            allKnownFirstParty.addAll(manifest.localCssClasses());
        }
        var taskRuntime = getRuntimeClasses().get();
        var missing = new ArrayList<UnstyledClass>();
        for (var manifest : manifests) {
            if (!manifest.failOnUnstyledCss() && manifest.checkedCssClassPrefixes().isEmpty()) continue;

            var known = new TreeSet<String>();
            if (manifest.failOnUnstyledCss()) {
                var hasModuleCss = !manifest.localCssClasses().isEmpty() || !manifest.externalCssClasses().isEmpty();
                if (hasModuleCss) {
                    known.addAll(manifest.localCssClasses());
                    known.addAll(manifest.externalCssClasses());
                    known.addAll(manifest.runtimeCssClasses());
                    known.addAll(taskRuntime);
                } else {
                    known.addAll(allKnownFirstParty);
                    known.addAll(allRuntimeClasses);
                }
            } else {
                known.addAll(allKnownFirstParty);
                known.addAll(manifest.externalCssClasses());
                known.addAll(allRuntimeClasses);
            }

            for (var use : manifest.templateClasses()) {
                var check = manifest.failOnUnstyledCss() ||
                        matchesAnyPrefix(use.className(), manifest.checkedCssClassPrefixes());
                if (check && !known.contains(use.className())) {
                    missing.add(new UnstyledClass(use.className(), use.file(), use.line(), manifest.modulePath()));
                }
            }
        }
        missing.sort(Comparator.comparing(UnstyledClass::file)
                .thenComparingInt(UnstyledClass::line)
                .thenComparing(UnstyledClass::className));
        return missing;
    }

    private List<ThimCssModuleManifest.ModuleManifest> readManifests(Set<File> files) throws IOException {
        var manifests = new ArrayList<ThimCssModuleManifest.ModuleManifest>();
        for (var file : sortedFiles(files)) {
            if (file.isFile()) {
                manifests.add(ThimCssModuleManifest.read(file.toPath()));
            }
        }
        return manifests;
    }

    private ExtendedUsage scanUsage(List<File> files) throws IOException {
        var tokens = new LinkedHashSet<String>();
        var prefixes = new LinkedHashSet<String>();
        var idTokens = new LinkedHashSet<String>();
        var idPrefixes = new LinkedHashSet<String>();
        var varRefs = new LinkedHashSet<String>();
        var varPrefixes = new LinkedHashSet<String>();
        for (var file : files) {
            var path = file.toPath();
            var ext = extension(path);
            if (!file.isFile() || nodeModulesPath(path) || !USAGE_EXTENSIONS.contains(ext)) {
                continue;
            }
            var content = Files.readString(path, StandardCharsets.UTF_8);
            collectLiterals(content, tokens, prefixes, idTokens, idPrefixes);
            collectVarReferences(content, varRefs, varPrefixes);
            if (ext.equals("html") || ext.equals("htm")) {
                var scan = HtmlTagScanner.scan(content);
                for (var tag : scan.tags()) {
                    var field = tag.attributes().get("th:field");
                    if (field != null && field.value() != null) {
                        var fieldName = stripFieldExpression(field.value());
                        if (!fieldName.isEmpty()) {
                            idTokens.add(fieldName);
                            var typeAttr = tag.attributes().get("type");
                            var valAttr = tag.attributes().get("value");
                            if (tag.name().equals("input") &&
                                    typeAttr != null && "radio".equalsIgnoreCase(typeAttr.value()) &&
                                    valAttr != null && valAttr.value() != null) {
                                idTokens.add(fieldName + "." + valAttr.value());
                            }
                        }
                    }
                    var thId = tag.attributes().get("th:id");
                    if (thId != null && thId.value() != null) {
                        ThimIdReferenceCheck.collectDynamicIdExpression(thId.value(), idTokens, idPrefixes);
                    }
                }
            }
        }
        return new ExtendedUsage(tokens, prefixes, idTokens, idPrefixes, varRefs, varPrefixes);
    }

    static String stripFieldExpression(String expression) {
        var trimmed = expression.trim();
        if (trimmed.startsWith("*{") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    static Set<String> classNames(String css) {
        return parseCss(css).classes();
    }

    static CssDefinitions parseCss(String css) {
        var acc = new CssAccumulator();
        parseCssBlock(css, 0, css.length(), false, acc);
        return new CssDefinitions(acc.classes, acc.ids, acc.variables, acc.varRefs, acc.varPrefixes);
    }

    private static void parseCssBlock(String css, int start, int end, boolean declarationsOnly, CssAccumulator acc) {
        var index = start;
        while (index < end) {
            index = skipCssTrivia(css, index, end);
            if (index >= end) break;
            if (css.charAt(index) == '}') {
                index++;
                continue;
            }

            var preludeStart = index;
            var parenDepth = 0;
            var bracketDepth = 0;
            Character quote = null;
            while (index < end) {
                var ch = css.charAt(index);
                if (quote != null) {
                    if (ch == '\\' && index + 1 < end) index += 2;
                    else {
                        if (ch == quote) quote = null;
                        index++;
                    }
                    continue;
                }
                if (css.startsWith("/*", index)) {
                    var commentEnd = css.indexOf("*/", index + 2);
                    index = commentEnd < 0 || commentEnd + 2 > end ? end : commentEnd + 2;
                    continue;
                }
                if (ch == '"' || ch == '\'') {
                    quote = ch;
                    index++;
                    continue;
                }
                if (ch == '(') {
                    parenDepth++;
                    index++;
                    continue;
                }
                if (ch == ')') {
                    if (parenDepth > 0) parenDepth--;
                    index++;
                    continue;
                }
                if (ch == '[') {
                    bracketDepth++;
                    index++;
                    continue;
                }
                if (ch == ']') {
                    if (bracketDepth > 0) bracketDepth--;
                    index++;
                    continue;
                }
                if (parenDepth == 0 && bracketDepth == 0 && (ch == ';' || ch == '{' || ch == '}')) {
                    break;
                }
                index++;
            }

            var prelude = css.substring(preludeStart, index);
            var terminator = index < end ? css.charAt(index) : '\0';

            if (terminator == ';' || terminator == '}' || terminator == '\0') {
                parseDeclaration(prelude, acc);
                if (terminator == ';') index++;
                else break;
                continue;
            }

            // terminator == '{'
            var blockStart = index + 1;
            var blockEnd = findMatchingBrace(css, blockStart, end);
            index = blockEnd < end ? blockEnd + 1 : end;

            var cleanPrelude = stripLeadingCssTrivia(prelude);
            if (cleanPrelude.startsWith("@")) {
                var atRule = readAtRuleName(cleanPrelude);
                if (atRule.equals("@property")) {
                    var rest = cleanPrelude.substring("@property".length()).trim();
                    var varName = readCustomPropertyToken(rest);
                    if (varName != null) acc.variables.add(varName);
                    parseCssBlock(css, blockStart, blockEnd, true, acc);
                } else if (atRule.equals("@keyframes") || atRule.endsWith("-keyframes")) {
                    parseKeyframesBlock(css, blockStart, blockEnd, acc);
                } else if (DECLARATION_ONLY_AT_RULES.contains(atRule)) {
                    parseCssBlock(css, blockStart, blockEnd, true, acc);
                } else if (atRule.equals("@scope")) {
                    extractScopeSelectors(cleanPrelude, acc);
                    parseCssBlock(css, blockStart, blockEnd, false, acc);
                } else {
                    parseCssBlock(css, blockStart, blockEnd, false, acc);
                }
            } else if (declarationsOnly) {
                parseDeclaration(prelude, acc);
            } else if (cleanPrelude.startsWith("--") && cleanPrelude.indexOf(':') > 0) {
                parseDeclaration(prelude + "{" + css.substring(blockStart, blockEnd) + "}", acc);
            } else {
                extractSelectors(prelude, acc);
                parseCssBlock(css, blockStart, blockEnd, false, acc);
            }
        }
    }

    private static void parseKeyframesBlock(String css, int start, int end, CssAccumulator acc) {
        var index = start;
        while (index < end) {
            index = skipCssTrivia(css, index, end);
            if (index >= end) break;
            var brace = css.indexOf('{', index);
            if (brace < 0 || brace >= end) break;
            var blockStart = brace + 1;
            var blockEnd = findMatchingBrace(css, blockStart, end);
            parseCssBlock(css, blockStart, blockEnd, true, acc);
            index = blockEnd < end ? blockEnd + 1 : end;
        }
    }

    private static void extractScopeSelectors(String prelude, CssAccumulator acc) {
        var index = 0;
        while ((index = prelude.indexOf('(', index)) >= 0) {
            var depth = 1;
            var pos = index + 1;
            while (pos < prelude.length() && depth > 0) {
                var ch = prelude.charAt(pos);
                if (ch == '(') depth++;
                else if (ch == ')') depth--;
                pos++;
            }
            if (depth == 0) {
                extractSelectors(prelude.substring(index + 1, pos - 1), acc);
            }
            index = pos;
        }
    }

    private static void parseDeclaration(String text, CssAccumulator acc) {
        var clean = stripCssComments(text).trim();
        if (clean.isEmpty() || clean.startsWith("@")) return;
        var colon = indexOfTopLevelColon(clean);
        if (colon <= 0) return;
        var property = clean.substring(0, colon).trim();
        var value = clean.substring(colon + 1);
        if (isCustomPropertyName(property)) {
            acc.variables.add(property);
        }
        collectVarReferences(value, acc.varRefs, acc.varPrefixes);
    }

    private static void extractSelectors(String prelude, CssAccumulator acc) {
        var index = 0;
        var length = prelude.length();
        Character quote = null;
        var bracketDepth = 0;
        while (index < length) {
            var ch = prelude.charAt(index);
            if (quote != null) {
                if (ch == '\\' && index + 1 < length) index += 2;
                else {
                    if (ch == quote) quote = null;
                    index++;
                }
                continue;
            }
            if (prelude.startsWith("/*", index)) {
                var commentEnd = prelude.indexOf("*/", index + 2);
                index = commentEnd < 0 ? length : commentEnd + 2;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                index++;
                continue;
            }
            if (ch == '[') {
                bracketDepth++;
                index++;
                continue;
            }
            if (ch == ']') {
                if (bracketDepth > 0) bracketDepth--;
                index++;
                continue;
            }
            if (bracketDepth > 0) {
                index++;
                continue;
            }
            if (ch == '.' && classStart(prelude, index + 1)) {
                var identifier = readCssIdentifier(prelude, index + 1);
                if (!identifier.value().isBlank()) acc.classes.add(identifier.value());
                index = identifier.end();
                continue;
            }
            if (ch == '#' && classStart(prelude, index + 1)) {
                var identifier = readCssIdentifier(prelude, index + 1);
                if (!identifier.value().isBlank()) acc.ids.add(identifier.value());
                index = identifier.end();
                continue;
            }
            index++;
        }
    }

    static void collectVarReferences(String source, Set<String> varRefs, Set<String> varPrefixes) {
        var length = source.length();
        var index = 0;
        Character quote = null;
        while (index < length) {
            var ch = source.charAt(index);
            if (quote == null) {
                if (source.startsWith("<!--", index)) {
                    var end = source.indexOf("-->", index + 4);
                    index = end < 0 ? length : end + 3;
                    continue;
                }
                if (source.startsWith("/*", index)) {
                    var end = source.indexOf("*/", index + 2);
                    index = end < 0 ? length : end + 2;
                    continue;
                }
                if (source.startsWith("//", index) && (index == 0 || source.charAt(index - 1) != ':')) {
                    var end = source.indexOf('\n', index + 2);
                    index = end < 0 ? length : end + 1;
                    continue;
                }
                if (ch == '"' || ch == '\'' || ch == '`') {
                    quote = ch;
                    index++;
                    continue;
                }
            } else {
                if (ch == '\\' && index + 1 < length) {
                    index += 2;
                    continue;
                }
                if (ch == quote) {
                    quote = null;
                    index++;
                    continue;
                }
            }

            if ((ch == 'v' || ch == 'V') && source.regionMatches(true, index, "var(", 0, 4) &&
                    (index == 0 || !isIdentChar(source.charAt(index - 1)))) {
                var pos = index + 4;
                while (pos < length && Character.isWhitespace(source.charAt(pos))) pos++;
                if (pos + 2 < length && source.charAt(pos) == '-' && source.charAt(pos + 1) == '-') {
                    var nameEnd = readCustomPropertyEnd(source, pos);
                    if (nameEnd > pos + 2) {
                        var name = source.substring(pos, nameEnd);
                        if (isInterpolatedNext(source, nameEnd)) {
                            varPrefixes.add(name);
                        } else {
                            varRefs.add(name);
                        }
                        index = nameEnd;
                        continue;
                    }
                }
            } else if (ch == 'g' && source.startsWith("getPropertyValue(", index) &&
                    (index == 0 || !isIdentChar(source.charAt(index - 1)))) {
                var pos = index + "getPropertyValue(".length();
                while (pos < length && Character.isWhitespace(source.charAt(pos))) pos++;
                if (pos < length && (source.charAt(pos) == '\'' || source.charAt(pos) == '"' || source.charAt(pos) == '`')) {
                    var argQuote = source.charAt(pos++);
                    if (pos + 2 < length && source.charAt(pos) == '-' && source.charAt(pos + 1) == '-') {
                        var nameEnd = readCustomPropertyEnd(source, pos);
                        if (nameEnd > pos + 2) {
                            var name = source.substring(pos, nameEnd);
                            if (nameEnd < length && source.charAt(nameEnd) == argQuote) {
                                varRefs.add(name);
                            } else if (isInterpolatedNext(source, nameEnd)) {
                                varPrefixes.add(name);
                            }
                        }
                    }
                }
            }
            index++;
        }
    }

    private static boolean isInterpolatedNext(String source, int index) {
        if (index >= source.length()) return false;
        var ch = source.charAt(index);
        if (ch == '$') {
            return index + 1 < source.length() &&
                    (source.charAt(index + 1) == '{' || Character.isLetter(source.charAt(index + 1)) || source.charAt(index + 1) == '_');
        }
        return false;
    }

    static Usage collectLiterals(String source, Set<String> tokens, Set<String> prefixes) {
        return collectLiterals(source, tokens, prefixes, new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    private static Usage collectLiterals(
            String source,
            Set<String> tokens,
            Set<String> prefixes,
            Set<String> idTokens,
            Set<String> idPrefixes
    ) {
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
            addLiteral(literal.toString(), tokens, prefixes, idTokens, idPrefixes);
        }
        return new Usage(tokens, prefixes);
    }

    private static void addLiteral(
            String literal,
            Set<String> tokens,
            Set<String> prefixes,
            Set<String> idTokens,
            Set<String> idPrefixes
    ) {
        collectInterpolationLiterals(literal, tokens, prefixes, idTokens, idPrefixes);
        var segments = interpolationSegments(literal);
        for (var i = 0; i < segments.size(); i++) {
            var segment = segments.get(i);
            for (var token : classTokens(segment)) {
                tokens.add(token);
                if (dynamicPrefix(token)) prefixes.add(token);
            }
            var segmentIds = idTokens(segment);
            idTokens.addAll(segmentIds);
            if (i < segments.size() - 1 && !segmentIds.isEmpty()) {
                var last = segmentIds.getLast();
                if ((last.endsWith("-") || last.endsWith("_")) && last.length() >= 2 && segment.endsWith(last)) {
                    idPrefixes.add(last);
                }
            }
        }
    }

    private static void collectInterpolationLiterals(
            String literal,
            Set<String> tokens,
            Set<String> prefixes,
            Set<String> idTokens,
            Set<String> idPrefixes
    ) {
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
            collectLiterals(literal.substring(start, end), tokens, prefixes, idTokens, idPrefixes);
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

    private static List<String> idTokens(String value) {
        var tokens = new ArrayList<String>();
        var index = 0;
        while (index < value.length()) {
            var ch = value.charAt(index);
            if (!isIdTokenChar(ch)) {
                index++;
                continue;
            }
            var start = index++;
            while (index < value.length() && isIdTokenChar(value.charAt(index))) index++;
            tokens.add(value.substring(start, index));
        }
        return tokens;
    }

    private static boolean isIdTokenChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '-' || ch == '_';
    }

    private static boolean isIdentChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '-' || ch == '_';
    }

    private static boolean isCustomPropertyName(String name) {
        if (name.length() <= 2 || !name.startsWith("--")) return false;
        for (var i = 2; i < name.length(); i++) {
            var ch = name.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') return false;
        }
        return true;
    }

    private static String readCustomPropertyToken(String text) {
        if (!text.startsWith("--")) return null;
        var end = readCustomPropertyEnd(text, 0);
        return end > 2 ? text.substring(0, end) : null;
    }

    private static int readCustomPropertyEnd(String source, int start) {
        var pos = start + 2;
        while (pos < source.length()) {
            var ch = source.charAt(pos);
            if (!Character.isLetterOrDigit(ch) && ch != '-' && ch != '_') break;
            pos++;
        }
        return pos;
    }

    private static int skipCssTrivia(String css, int index, int end) {
        while (index < end) {
            if (Character.isWhitespace(css.charAt(index))) {
                index++;
            } else if (css.startsWith("/*", index)) {
                var commentEnd = css.indexOf("*/", index + 2);
                index = commentEnd < 0 || commentEnd + 2 > end ? end : commentEnd + 2;
            } else {
                break;
            }
        }
        return index;
    }

    private static String stripLeadingCssTrivia(String text) {
        var index = skipCssTrivia(text, 0, text.length());
        return text.substring(index);
    }

    private static String stripCssComments(String text) {
        if (!text.contains("/*")) return text;
        var out = new StringBuilder(text.length());
        var index = 0;
        Character quote = null;
        while (index < text.length()) {
            var ch = text.charAt(index);
            if (quote != null) {
                out.append(ch);
                if (ch == '\\' && index + 1 < text.length()) {
                    out.append(text.charAt(index + 1));
                    index += 2;
                } else {
                    if (ch == quote) quote = null;
                    index++;
                }
            } else if (text.startsWith("/*", index)) {
                var end = text.indexOf("*/", index + 2);
                index = end < 0 ? text.length() : end + 2;
            } else {
                if (ch == '"' || ch == '\'') quote = ch;
                out.append(ch);
                index++;
            }
        }
        return out.toString();
    }

    private static int indexOfTopLevelColon(String text) {
        Character quote = null;
        var parenDepth = 0;
        for (var i = 0; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (quote != null) {
                if (ch == '\\' && i + 1 < text.length()) i++;
                else if (ch == quote) quote = null;
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(') {
                parenDepth++;
            } else if (ch == ')') {
                if (parenDepth > 0) parenDepth--;
            } else if (ch == ':' && parenDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String css, int start, int end) {
        var depth = 1;
        var index = start;
        Character quote = null;
        while (index < end) {
            var ch = css.charAt(index);
            if (quote != null) {
                if (ch == '\\' && index + 1 < end) index += 2;
                else {
                    if (ch == quote) quote = null;
                    index++;
                }
                continue;
            }
            if (css.startsWith("/*", index)) {
                var commentEnd = css.indexOf("*/", index + 2);
                index = commentEnd < 0 || commentEnd + 2 > end ? end : commentEnd + 2;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                index++;
                continue;
            }
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return index;
            }
            index++;
        }
        return end;
    }

    private static String readAtRuleName(String cleanPrelude) {
        var pos = 1;
        while (pos < cleanPrelude.length()) {
            var ch = cleanPrelude.charAt(pos);
            if (!Character.isLetterOrDigit(ch) && ch != '-') break;
            pos++;
        }
        return cleanPrelude.substring(0, pos).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAnyPrefix(String value, Iterable<String> prefixes) {
        for (var prefix : prefixes) {
            if (!prefix.isEmpty() && value.startsWith(prefix)) return true;
        }
        return false;
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
            Map<String, Set<String>> classDefinitions,
            Set<String> usedClasses,
            Set<String> prefixUsedClasses,
            Set<String> unusedClasses,
            Map<String, Set<String>> idDefinitions,
            Set<String> unusedIds,
            Map<String, Set<String>> varDefinitions,
            Set<String> unusedVariables,
            List<UnstyledClass> unstyledClasses
    ) throws IOException {
        var report = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(report.getParent());
        var json = new StringBuilder();
        json.append("{\n")
                .append("  \"defined\": ").append(classDefinitions.size()).append(",\n")
                .append("  \"used\": ").append(usedClasses.size()).append(",\n")
                .append("  \"prefixUsed\": ").append(prefixUsedClasses.size()).append(",\n")
                .append("  \"unused\": ").append(unusedClasses.size()).append(",\n")
                .append("  \"unusedClasses\": [");
        appendNamedFileEntries(json, unusedClasses, classDefinitions);
        json.append("],\n")
                .append("  \"definedIds\": ").append(idDefinitions.size()).append(",\n")
                .append("  \"unusedIds\": [");
        appendNamedFileEntries(json, unusedIds, idDefinitions);
        json.append("],\n")
                .append("  \"definedVariables\": ").append(varDefinitions.size()).append(",\n")
                .append("  \"unusedVariables\": [");
        appendNamedFileEntries(json, unusedVariables, varDefinitions);
        json.append("],\n")
                .append("  \"unstyledClasses\": [");
        var separator = "";
        for (var missing : unstyledClasses) {
            json.append(separator).append("\n    {\"name\": \"").append(json(missing.className()))
                    .append("\", \"file\": \"").append(json(missing.file()))
                    .append("\", \"line\": ").append(missing.line())
                    .append(", \"module\": \"").append(json(missing.module())).append("\"}");
            separator = ",";
        }
        if (!unstyledClasses.isEmpty()) json.append('\n').append("  ");
        json.append("]\n}\n");
        Files.writeString(report, json.toString(), StandardCharsets.UTF_8);
    }

    private static void appendNamedFileEntries(
            StringBuilder json,
            Set<String> names,
            Map<String, Set<String>> definitions
    ) {
        var separator = "";
        for (var name : names) {
            json.append(separator).append("\n    {\"name\": \"").append(json(name)).append("\", \"files\": [");
            var fileSeparator = "";
            for (var file : definitions.getOrDefault(name, Set.of())) {
                json.append(fileSeparator).append("\"").append(json(file)).append("\"");
                fileSeparator = ", ";
            }
            json.append("]}");
            separator = ",";
        }
        if (!names.isEmpty()) json.append('\n').append("  ");
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static List<File> sortedFiles(Set<File> files) {
        return files.stream().sorted(Comparator.comparing(File::getAbsolutePath)).toList();
    }

    static String relativePath(Path root, Path file) {
        var absolute = file.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute.getFileName())
                .toString().replace(File.separatorChar, '/');
    }

    static boolean ignoredCssPath(Path file) {
        for (var part : file) {
            var name = part.toString();
            if (name.equalsIgnoreCase("vendor") || name.equals("node_modules")) return true;
        }
        return false;
    }

    static boolean nodeModulesPath(Path file) {
        for (var part : file) {
            if (part.toString().equals("node_modules")) return true;
        }
        return false;
    }

    static String extension(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean hexDigit(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f' ||
                character >= 'A' && character <= 'F';
    }

    record Usage(Set<String> tokens, Set<String> prefixes) {}

    private record ExtendedUsage(
            Set<String> tokens,
            Set<String> prefixes,
            Set<String> idTokens,
            Set<String> idPrefixes,
            Set<String> varRefs,
            Set<String> varPrefixes
    ) {}

    record CssDefinitions(
            Set<String> classes,
            Set<String> ids,
            Set<String> variables,
            Set<String> varRefs,
            Set<String> varPrefixes
    ) {}

    private static final class CssAccumulator {
        final Set<String> classes = new LinkedHashSet<>();
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> variables = new LinkedHashSet<>();
        final Set<String> varRefs = new LinkedHashSet<>();
        final Set<String> varPrefixes = new LinkedHashSet<>();
    }

    private record UnstyledClass(String className, String file, int line, String module) {}

    private record CssIdentifier(String value, int end) {}
}
