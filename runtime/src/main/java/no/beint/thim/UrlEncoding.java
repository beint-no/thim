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
     * Why this function exists: generated renderers splice runtime values (an id, a name,
     * a search term) into URLs whose shape was fixed at compile time, e.g.
     * {@code "/feature/" + UrlEncoding.pathSegment(name)}. Without encoding, a value
     * containing {@code /}, {@code ?}, {@code #} or a {@code javascript:} scheme could
     * change the URL's structure or target. Encoding turns every such character into
     * inert {@code %XX} data, so a value can only ever fill its slot, never reshape the
     * URL. {@link java.net.URLEncoder} is not used because it implements HTML form
     * encoding: it writes a space as {@code +}, which inside a path segment decodes back
     * to a literal plus and corrupts the value.
     *
     * How it works: percent-encoding operates on bytes, not characters, so an unsafe
     * character is converted to its UTF-8 bytes (1-4 of them, depending on the character)
     * and each byte is written as {@code %XX}. The branches below are the four UTF-8 size
     * classes; the hex constants are numeric boundaries and byte markers from the UTF-8
     * spec, not characters. Same conversion as {@link HtmlOutput#text(String)}, just
     * percent-escaped.
     *
     * <pre>
     * "a b"  -> "a%20b"          (space: 1 byte)
     * "æøå"  -> "%C3%A6%C3%B8%C3%A5"  (2 bytes each)
     * "✓"    -> "%E2%9C%93"      (3 bytes)
     * "😀"   -> "%F0%9F%98%80"   (4 bytes, stored as a Java surrogate pair)
     * </pre>
     */
    private static String encodeFrom(String value, int start) {
        var output = new StringBuilder(value.length() + 16);
        output.append(value, 0, start);
        for (var index = start; index < value.length(); index++) {
            var character = value.charAt(index);
            if (unreserved(character)) {
                output.append(character);
            } else if (character < 0x80) {
                // Below 128: plain ASCII (space, '/', '?', ':'), a single byte used as-is.
                percent(output, character);
            } else if (character < 0x800) {
                // 128..2047 (e.g. 'æ', 'ø'): two bytes. The character's bits are split
                // into 5 + 6 and stamped with the UTF-8 markers 110xxxxx and 10xxxxxx
                // (0xc0 and 0x80); 0x3f keeps only the low six bits.
                percent(output, 0xc0 | character >> 6);
                percent(output, 0x80 | character & 0x3f);
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                // Emoji and other characters above 65535 are stored as two Java chars
                // (a surrogate pair). Combine them back into one code point and split
                // its bits into four bytes: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx.
                var codePoint = Character.toCodePoint(character, value.charAt(++index));
                percent(output, 0xf0 | codePoint >> 18);
                percent(output, 0x80 | codePoint >> 12 & 0x3f);
                percent(output, 0x80 | codePoint >> 6 & 0x3f);
                percent(output, 0x80 | codePoint & 0x3f);
            } else if (Character.isSurrogate(character)) {
                // Half of a surrogate pair without its partner: broken input. Emit the
                // Unicode replacement character '�' (bytes EF BF BD) instead.
                percent(output, 0xef);
                percent(output, 0xbf);
                percent(output, 0xbd);
            } else {
                // 2048..65535 (e.g. '✓', CJK): three bytes, bits split into 4 + 6 + 6
                // with markers 1110xxxx 10xxxxxx 10xxxxxx.
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
