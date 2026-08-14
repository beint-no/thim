package no.beint.thim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlOutputTest {
    @Test
    void escapesHtmlSpecialsAndEncodesUtf8() throws IOException {
        assertEquals("Acme AS", render("Acme AS"));
        assertEquals("f", render("f"));
        assertEquals("A &amp; B &lt;x&gt; &#39;y&#39; &quot;z&quot;", render("A & B <x> 'y' \"z\""));
        assertEquals("æøå", render("æøå"));
        assertEquals("", render((String) null));
        assertEquals("", render(""));
    }

    @Test
    void replacesLoneSurrogates() throws IOException {
        assertEquals("\uFFFD", render("\uD800"));
        assertEquals("\uFFFD", render("\uDC00"));
        assertEquals("A\uFFFDB", render("A\uD800B"));
    }

    @Test
    void encodesSupplementaryCharactersAsFourUtf8Bytes() throws IOException {
        assertEquals("😀", render("😀"));
        assertEquals("A😀&amp;", render("A😀&"));
    }

    @Test
    void writesPrimitiveText() throws IOException {
        assertEquals("true", render(true));
        assertEquals("false", render(false));
        assertEquals("0", render(0));
        assertEquals("9", render(9));
        assertEquals("10", render(10));
        assertEquals("-42", render(-42));
        assertEquals("9223372036854775807", render(Long.MAX_VALUE));
        assertEquals("-9223372036854775808", render(Long.MIN_VALUE));
        assertEquals("-9223372036854775807", render(Long.MIN_VALUE + 1));
    }

    @Test
    void writesBoxedObjectsWithTheSameEncodingAsTheirTypedOverloads() throws IOException {
        assertEquals("12", renderObject(12));
        assertEquals("-3", renderObject(-3L));
        assertEquals("true", renderObject(Boolean.TRUE));
        assertEquals("plain", renderObject("plain"));
        assertEquals("", renderObject(null));
    }

    @Test
    void copiesRawBytesAndSafeHtml() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.raw(new byte[] {'<', 'b', '>'}, 0, 3);
        output.raw(new SafeHtml("<i>ok</i>"));
        output.flush();
        assertEquals("<b><i>ok</i>", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void urlValuesUseTheSameHtmlEscapingAsText() throws IOException {
        assertEquals("/a&amp;b", renderUrl(new TrustedUrl("/a&b")));
    }

    @Test
    void rawCopyLargerThanTheBufferGoesStraightToTheDestination() throws IOException {
        var payload = "0123456789abcdef".repeat(8).getBytes(StandardCharsets.US_ASCII);
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes, 8);
        output.raw(payload, 0, payload.length);
        output.text("&");
        output.flush();
        assertEquals("0123456789abcdef".repeat(8) + "&amp;", bytes.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 8, 16, 17, 64, 65, 1024})
    void bufferSizeDoesNotChangeEncodedBytes(int bufferSize) throws IOException {
        var samples = interestingStrings();
        samples.addAll(fixedSeedMixes());
        for (var sample : samples) {
            assertArrayEquals(encode(sample, 16 * 1024), encode(sample, bufferSize), sample);
        }
        for (var value : new long[] {0, 1, -1, 10, 99, 100, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1}) {
            assertEquals(render(value), render(value, bufferSize), Long.toString(value));
        }
    }

    @Test
    void matchesAStraightforwardReferenceEncoder() throws IOException {
        var samples = interestingStrings();
        samples.addAll(fixedSeedMixes());
        for (var sample : samples) {
            assertEquals(reference(sample), render(sample), sample);
        }
    }

    private static ArrayList<String> interestingStrings() {
        var samples = new ArrayList<String>();
        samples.add("");
        samples.add(" ");
        samples.add("&");
        samples.add("<");
        samples.add(">");
        samples.add("\"");
        samples.add("'");
        samples.add("&&&");
        samples.add("&<>\"'");
        samples.add("plain ASCII heading");
        samples.add("A & B <x> 'y' \"z\"");
        samples.add("&starts");
        samples.add("ends&");
        samples.add("mid&dle");
        samples.add("æøå");
        samples.add("日本語");
        samples.add("café");
        samples.add("A😀B");
        samples.add("\uD800");
        samples.add("\uDC00");
        samples.add("a".repeat(20_000));
        samples.add(("safe " + "&<>\"'" + " æ ").repeat(200));
        return samples;
    }

    private static ArrayList<String> fixedSeedMixes() {
        var random = RandomGeneratorFactory.getDefault().create(20260814L);
        var samples = new ArrayList<String>();
        var alphabet = "ABC xyz012&<>\"'æøå日\n\t".toCharArray();
        for (var n = 0; n < 80; n++) {
            var length = random.nextInt(1, 80);
            var text = new StringBuilder(length);
            for (var index = 0; index < length; index++) {
                if (random.nextInt(20) == 0) {
                    text.appendCodePoint(0x1F600 + random.nextInt(10));
                } else {
                    text.append(alphabet[random.nextInt(alphabet.length)]);
                }
            }
            samples.add(text.toString());
        }
        return samples;
    }

    private static String reference(String value) {
        var output = new StringBuilder();
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '&' -> output.append("&amp;");
                case '\'' -> output.append("&#39;");
                case '>' -> output.append("&gt;");
                case '<' -> output.append("&lt;");
                case '"' -> output.append("&quot;");
                default -> {
                    if (character < 0x80) {
                        output.append(character);
                    } else if (character < 0x800) {
                        output.appendCodePoint(character);
                    } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        output.appendCodePoint(Character.toCodePoint(character, value.charAt(++index)));
                    } else if (Character.isSurrogate(character)) {
                        output.append('\uFFFD');
                    } else {
                        output.appendCodePoint(character);
                    }
                }
            }
        }
        return output.toString();
    }

    private static byte[] encode(String value, int bufferSize) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes, bufferSize);
        output.text(value);
        output.flush();
        return bytes.toByteArray();
    }

    private static String render(String value) throws IOException {
        return render(value, 16 * 1024);
    }

    private static String render(String value, int bufferSize) throws IOException {
        return new String(encode(value, bufferSize), StandardCharsets.UTF_8);
    }

    private static String render(boolean value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.text(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String render(long value) throws IOException {
        return render(value, 16 * 1024);
    }

    private static String render(long value, int bufferSize) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes, bufferSize);
        output.text(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String renderObject(Object value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.text(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String renderUrl(TrustedUrl value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.url(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
