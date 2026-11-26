package no.beint.thim.gradle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight HTML tag scanner shared by CSS and ID reference checks.
 * Tracks 1-based line numbers, skips HTML comments and raw {@code <style>} bodies,
 * and captures {@code <script>} bodies with their starting line number.
 */
final class HtmlTagScanner {
    private HtmlTagScanner() {}

    record Attribute(String value, int line) {}

    record OpeningTag(String name, int line, Map<String, Attribute> attributes) {}

    record ScriptBlock(String body, int startLine) {}

    record ScanResult(List<OpeningTag> tags, List<ScriptBlock> scripts) {}

    static ScanResult scan(String html) {
        var tags = new ArrayList<OpeningTag>();
        var scripts = new ArrayList<ScriptBlock>();
        var length = html.length();
        var index = 0;
        var line = 1;
        while (index < length) {
            var ch = html.charAt(index);
            if (ch == '\n') {
                line++;
                index++;
                continue;
            }
            if (ch != '<') {
                index++;
                continue;
            }
            if (html.startsWith("<!--", index)) {
                var end = html.indexOf("-->", index + 4);
                var next = end < 0 ? length : end + 3;
                line += countNewlines(html, index, next);
                index = next;
                continue;
            }
            if (index + 1 >= length) {
                break;
            }
            var nextChar = html.charAt(index + 1);
            if (nextChar == '/' || nextChar == '!' || nextChar == '?') {
                var end = findTagEnd(html, index + 2);
                var next = end < 0 ? length : end + 1;
                line += countNewlines(html, index, next);
                index = next;
                continue;
            }
            if (!Character.isLetter(nextChar)) {
                index++;
                continue;
            }

            var tagLine = line;
            var tagEnd = findTagEnd(html, index + 1);
            if (tagEnd < 0) break;

            var tag = parseOpeningTag(html, index + 1, tagEnd, tagLine);
            if (tag != null) {
                tags.add(tag);
            }
            var selfClosing = isSelfClosing(html, index + 1, tagEnd);
            line += countNewlines(html, index, tagEnd + 1);
            index = tagEnd + 1;

            if (tag != null && !selfClosing && (tag.name().equals("script") || tag.name().equals("style"))) {
                var bodyStart = index;
                var bodyLine = line;
                var closeStart = findClosingRawTag(html, index, tag.name());
                var bodyEnd = closeStart < 0 ? length : closeStart;
                if (tag.name().equals("script") && bodyEnd > bodyStart) {
                    scripts.add(new ScriptBlock(html.substring(bodyStart, bodyEnd), bodyLine));
                }
                if (closeStart < 0) {
                    line += countNewlines(html, bodyStart, length);
                    index = length;
                } else {
                    var closeEnd = html.indexOf('>', closeStart + 2);
                    var next = closeEnd < 0 ? length : closeEnd + 1;
                    line += countNewlines(html, bodyStart, next);
                    index = next;
                }
            }
        }
        return new ScanResult(tags, scripts);
    }

    private static OpeningTag parseOpeningTag(String html, int start, int end, int startLine) {
        var pos = start;
        var line = startLine;
        while (pos < end && Character.isWhitespace(html.charAt(pos))) {
            if (html.charAt(pos) == '\n') line++;
            pos++;
        }
        var nameStart = pos;
        while (pos < end) {
            var ch = html.charAt(pos);
            if (Character.isWhitespace(ch) || ch == '/') break;
            pos++;
        }
        if (pos == nameStart) return null;
        var tagName = html.substring(nameStart, pos).toLowerCase(Locale.ROOT);
        var attributes = new LinkedHashMap<String, Attribute>();

        while (pos < end) {
            while (pos < end && (Character.isWhitespace(html.charAt(pos)) || html.charAt(pos) == '/')) {
                if (html.charAt(pos) == '\n') line++;
                pos++;
            }
            if (pos >= end) break;
            var attrLine = line;
            var attrStart = pos;
            while (pos < end) {
                var ch = html.charAt(pos);
                if (Character.isWhitespace(ch) || ch == '=' || ch == '/') break;
                pos++;
            }
            if (pos == attrStart) {
                pos++;
                continue;
            }
            var attrName = html.substring(attrStart, pos).toLowerCase(Locale.ROOT);
            while (pos < end && Character.isWhitespace(html.charAt(pos))) {
                if (html.charAt(pos) == '\n') line++;
                pos++;
            }
            String value = null;
            if (pos < end && html.charAt(pos) == '=') {
                pos++;
                while (pos < end && Character.isWhitespace(html.charAt(pos))) {
                    if (html.charAt(pos) == '\n') line++;
                    pos++;
                }
                if (pos < end && (html.charAt(pos) == '"' || html.charAt(pos) == '\'')) {
                    var quote = html.charAt(pos++);
                    var valueStart = pos;
                    while (pos < end && html.charAt(pos) != quote) {
                        if (html.charAt(pos) == '\n') line++;
                        pos++;
                    }
                    value = html.substring(valueStart, pos);
                    if (pos < end) pos++;
                } else {
                    var valueStart = pos;
                    while (pos < end && !Character.isWhitespace(html.charAt(pos))) {
                        pos++;
                    }
                    value = html.substring(valueStart, pos);
                }
            }
            attributes.putIfAbsent(attrName, new Attribute(value, attrLine));
        }
        return new OpeningTag(tagName, startLine, attributes);
    }

    private static int findTagEnd(String html, int start) {
        char quote = 0;
        for (var i = start; i < html.length(); i++) {
            var ch = html.charAt(i);
            if (quote == 0) {
                if (ch == '"' || ch == '\'') quote = ch;
                else if (ch == '>') return i;
            } else if (ch == quote) {
                quote = 0;
            }
        }
        return -1;
    }

    private static boolean isSelfClosing(String html, int start, int end) {
        var i = end - 1;
        while (i >= start && Character.isWhitespace(html.charAt(i))) i--;
        return i >= start && html.charAt(i) == '/';
    }

    private static int findClosingRawTag(String html, int start, String tagName) {
        var needle = "</" + tagName;
        var length = html.length();
        var pos = start;
        while (pos < length) {
            var idx = indexOfIgnoreCase(html, needle, pos);
            if (idx < 0) return -1;
            var after = idx + needle.length();
            if (after >= length || Character.isWhitespace(html.charAt(after)) || html.charAt(after) == '>') {
                return idx;
            }
            pos = after;
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int fromIndex) {
        var limit = haystack.length() - needle.length();
        for (var i = fromIndex; i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static int countNewlines(String text, int start, int end) {
        var count = 0;
        for (var i = start; i < end; i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }
}
