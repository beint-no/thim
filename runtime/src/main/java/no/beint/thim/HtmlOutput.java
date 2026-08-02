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

    private final OutputStream destination;
    private final byte[] buffer;
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
        if (length >= buffer.length) {
            flushBuffer();
            destination.write(value, offset, length);
            return;
        }
        if (length > buffer.length - position) {
            flushBuffer();
        }
        System.arraycopy(value, offset, buffer, position, length);
        position += length;
    }

    public void text(Object value) throws IOException {
        if (value != null) {
            text(value.toString());
        }
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
        if (value < 0) {
            byteValue('-');
            value = -value;
        }
        var divisor = 1L;
        while (value / divisor >= 10) {
            divisor *= 10;
        }
        do {
            byteValue((int) ('0' + value / divisor));
            value %= divisor;
            divisor /= 10;
        } while (divisor != 0);
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
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '&' -> raw(AMPERSAND, 0, AMPERSAND.length);
                case '\'' -> raw(APOSTROPHE, 0, APOSTROPHE.length);
                case '>' -> raw(GREATER_THAN, 0, GREATER_THAN.length);
                case '<' -> raw(LESS_THAN, 0, LESS_THAN.length);
                case '"' -> raw(QUOTATION_MARK, 0, QUOTATION_MARK.length);
                default -> {
                    if (character < 0x80) {
                        byteValue(character);
                    } else if (character < 0x800) {
                        byteValue(0xc0 | character >> 6);
                        byteValue(0x80 | character & 0x3f);
                    } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        var codePoint = Character.toCodePoint(character, value.charAt(++index));
                        byteValue(0xf0 | codePoint >> 18);
                        byteValue(0x80 | codePoint >> 12 & 0x3f);
                        byteValue(0x80 | codePoint >> 6 & 0x3f);
                        byteValue(0x80 | codePoint & 0x3f);
                    } else if (Character.isSurrogate(character)) {
                        raw(REPLACEMENT_CHARACTER, 0, REPLACEMENT_CHARACTER.length);
                    } else {
                        byteValue(0xe0 | character >> 12);
                        byteValue(0x80 | character >> 6 & 0x3f);
                        byteValue(0x80 | character & 0x3f);
                    }
                }
            }
        }
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

    private void byteValue(int value) throws IOException {
        if (position == buffer.length) {
            flushBuffer();
        }
        buffer[position++] = (byte) value;
    }

    private void flushBuffer() throws IOException {
        if (position == 0) {
            return;
        }
        destination.write(buffer, 0, position);
        position = 0;
    }
}
