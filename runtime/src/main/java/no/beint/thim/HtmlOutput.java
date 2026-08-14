package no.beint.thim;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class HtmlOutput {
    private static final byte[] AMPERSAND = "&amp;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] APOSTROPHE = "&#39;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GREATER_THAN = "&gt;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LESS_THAN = "&lt;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUOTATION_MARK = "&quot;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REPLACEMENT_CHARACTER = {(byte) 0xef, (byte) 0xbf, (byte) 0xbd};
    private static final byte[] TRUE = "true".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALSE = "false".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MIN_LONG = "-9223372036854775808".getBytes(StandardCharsets.US_ASCII);
    private static final long HTML_SPECIALS =
            1L << '&' | 1L << '\'' | 1L << '<' | 1L << '>' | 1L << '"';

    private final OutputStream destination;
    private final byte[] buffer;
    private final byte[] digits = new byte[20];
    private int position;

    public HtmlOutput(OutputStream destination) {
        this(destination, 16 * 1024);
    }

    public HtmlOutput(OutputStream destination, int bufferSize) {
        this.destination = Objects.requireNonNull(destination);
        if (bufferSize < 4) {
            throw new IllegalArgumentException("bufferSize must be at least 4");
        }
        buffer = new byte[bufferSize];
    }

    public void raw(byte[] value, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, value.length);
        if (length <= buffer.length - position) {
            System.arraycopy(value, offset, buffer, position, length);
            position += length;
            return;
        }
        flushBuffer();
        if (length >= buffer.length) {
            destination.write(value, offset, length);
            return;
        }
        System.arraycopy(value, offset, buffer, position, length);
        position += length;
    }

    public void text(Object value) throws IOException {
        switch (value) {
            case null -> {
            }
            case String string -> text(string);
            case Integer number -> text(number.intValue());
            case Long number -> text(number.longValue());
            case Boolean flag -> text(flag.booleanValue());
            default -> text(value.toString());
        }
    }

    public void raw(SafeHtml value) throws IOException {
        var bytes = value.value().getBytes(StandardCharsets.UTF_8);
        raw(bytes, 0, bytes.length);
    }

    public void url(TrustedUrl value) throws IOException {
        text(value.value());
    }

    public void text(boolean value) throws IOException {
        var bytes = value ? TRUE : FALSE;
        raw(bytes, 0, bytes.length);
    }

    public void text(int value) throws IOException {
        text((long) value);
    }

    public void text(long value) throws IOException {
        if (value == Long.MIN_VALUE) {
            raw(MIN_LONG, 0, MIN_LONG.length);
            return;
        }
        var negative = value < 0;
        if (negative) {
            value = -value;
        }
        var cursor = 20;
        do {
            digits[--cursor] = (byte) ('0' + value % 10);
            value /= 10;
        } while (value != 0);
        if (negative) {
            digits[--cursor] = '-';
        }
        raw(digits, cursor, 20 - cursor);
    }

    public void text(float value) throws IOException {
        text(Float.toString(value));
    }

    public void text(double value) throws IOException {
        text(Double.toString(value));
    }

    public void text(String value) throws IOException {
        if (value == null) {
            return;
        }
        var dest = buffer;
        var pos = position;
        var capacity = dest.length;
        var length = value.length();
        for (var index = 0; index < length; index++) {
            var character = value.charAt(index);
            if (character < 0x80) {
                if (!htmlSpecial(character)) {
                    if (pos == capacity) {
                        destination.write(dest, 0, pos);
                        pos = 0;
                    }
                    dest[pos++] = (byte) character;
                    continue;
                }
                position = pos;
                writeEntity(switch (character) {
                    case '&' -> AMPERSAND;
                    case '\'' -> APOSTROPHE;
                    case '>' -> GREATER_THAN;
                    case '<' -> LESS_THAN;
                    default -> QUOTATION_MARK;
                });
                pos = position;
                continue;
            }
            if (character < 0x800) {
                if (capacity - pos < 2) {
                    destination.write(dest, 0, pos);
                    pos = 0;
                }
                dest[pos++] = (byte) (0xc0 | character >> 6);
                dest[pos++] = (byte) (0x80 | character & 0x3f);
                continue;
            }
            if (Character.isHighSurrogate(character) && index + 1 < length
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                var codePoint = Character.toCodePoint(character, value.charAt(++index));
                if (capacity - pos < 4) {
                    destination.write(dest, 0, pos);
                    pos = 0;
                }
                dest[pos++] = (byte) (0xf0 | codePoint >> 18);
                dest[pos++] = (byte) (0x80 | codePoint >> 12 & 0x3f);
                dest[pos++] = (byte) (0x80 | codePoint >> 6 & 0x3f);
                dest[pos++] = (byte) (0x80 | codePoint & 0x3f);
                continue;
            }
            if (Character.isSurrogate(character)) {
                position = pos;
                writeEntity(REPLACEMENT_CHARACTER);
                pos = position;
                continue;
            }
            if (capacity - pos < 3) {
                destination.write(dest, 0, pos);
                pos = 0;
            }
            dest[pos++] = (byte) (0xe0 | character >> 12);
            dest[pos++] = (byte) (0x80 | character >> 6 & 0x3f);
            dest[pos++] = (byte) (0x80 | character & 0x3f);
        }
        position = pos;
    }

    public void flush() throws IOException {
        flushBuffer();
        destination.flush();
    }

    public static byte[] resource(Class<?> owner, String name) {
        try (var input = owner.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("Missing generated template resource " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean htmlSpecial(char character) {
        return character < 64 && (HTML_SPECIALS & 1L << character) != 0;
    }

    private void writeEntity(byte[] entity) throws IOException {
        raw(entity, 0, entity.length);
    }

    private void flushBuffer() throws IOException {
        if (position == 0) {
            return;
        }
        destination.write(buffer, 0, position);
        position = 0;
    }
}
