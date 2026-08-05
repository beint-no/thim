package no.beint.thim;

import java.util.Objects;

/**
 * Percent-encodes dynamic values spliced into {@code @{...}} URLs by generated renderers.
 * Everything outside the RFC 3986 unreserved set is encoded, so a value can never add
 * path segments, query parameters, or a scheme to the URL shape fixed at compile time.
 */
public final class UrlEncoding {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UrlEncoding() {}

    public static String pathSegment(Object value) {
        return encode(Objects.requireNonNull(value).toString());
    }

    public static String query(Object value) {
        return encode(Objects.requireNonNull(value).toString());
    }

    private static String encode(String value) {
        // Fast path: most values (ids, slugs) need no encoding and are returned unchanged.
        for (var index = 0; index < value.length(); index++) {
            if (!unreserved(value.charAt(index))) {
                return encodeFrom(value, index);
            }
        }
        return value;
    }

    /**
     * Percent-encoding operates on UTF-8 bytes, so each unsafe character is first
     * converted to its 1-4 UTF-8 bytes and each byte is written as {@code %XX}.
     * The branches mirror the UTF-8 layout used by {@link HtmlOutput#text(String)}.
     */
    private static String encodeFrom(String value, int start) {
        var output = new StringBuilder(value.length() + 16);
        output.append(value, 0, start);
        for (var index = start; index < value.length(); index++) {
            var character = value.charAt(index);
            if (unreserved(character)) {
                output.append(character);
            } else if (character < 0x80) {
                // ASCII: one byte, e.g. ' ' -> %20
                percent(output, character);
            } else if (character < 0x800) {
                // Two bytes 110xxxxx 10xxxxxx, e.g. 'æ' -> %C3%A6
                percent(output, 0xc0 | character >> 6);
                percent(output, 0x80 | character & 0x3f);
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                // Surrogate pair (emoji etc.): four bytes 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
                var codePoint = Character.toCodePoint(character, value.charAt(++index));
                percent(output, 0xf0 | codePoint >> 18);
                percent(output, 0x80 | codePoint >> 12 & 0x3f);
                percent(output, 0x80 | codePoint >> 6 & 0x3f);
                percent(output, 0x80 | codePoint & 0x3f);
            } else if (Character.isSurrogate(character)) {
                // Unpaired surrogate: emit U+FFFD replacement character instead
                percent(output, 0xef);
                percent(output, 0xbf);
                percent(output, 0xbd);
            } else {
                // Remaining BMP: three bytes 1110xxxx 10xxxxxx 10xxxxxx, e.g. '✓' -> %E2%9C%93
                percent(output, 0xe0 | character >> 12);
                percent(output, 0x80 | character >> 6 & 0x3f);
                percent(output, 0x80 | character & 0x3f);
            }
        }
        return output.toString();
    }

    private static boolean unreserved(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '-' || character == '.' || character == '_' || character == '~';
    }

    private static void percent(StringBuilder output, int byteValue) {
        output.append('%').append(HEX[byteValue >> 4 & 0xf]).append(HEX[byteValue & 0xf]);
    }
}
