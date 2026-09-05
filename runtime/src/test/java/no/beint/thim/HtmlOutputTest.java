package no.beint.thim;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlOutputTest {
    @Test
    void escapesHtmlSpecialsAndEncodesUtf8() throws IOException {
        assertEquals("Acme AS", render("Acme AS"));
        assertEquals("A &amp; B &lt;x&gt; &#39;y&#39; &quot;z&quot;", render("A & B <x> 'y' \"z\""));
        assertEquals("æøå", render("æøå"));
        assertEquals("", render((String) null));
    }

    @Test
    void replacesLoneSurrogates() throws IOException {
        assertEquals("\uFFFD", render("\uD800"));
    }

    @Test
    void writesPrimitiveText() throws IOException {
        assertEquals("true", render(true));
        assertEquals("false", render(false));
        assertEquals("0", render(0));
        assertEquals("-42", render(-42));
        assertEquals("-9223372036854775808", render(Long.MIN_VALUE));
    }

    @Test
    void writesIntegersAcrossBufferBoundaries() throws IOException {
        var random = new Random(42);
        var values = new long[1000];
        values[0] = Long.MIN_VALUE;
        values[1] = Long.MAX_VALUE;
        values[2] = 0;
        values[3] = -1;
        var index = 4;
        for (var power = 1L; power <= 1_000_000_000_000_000_000L; power *= 10) {
            for (var delta = -1; delta <= 1; delta++) {
                values[index++] = power + delta;
                values[index++] = -power + delta;
            }
            if (power == 1_000_000_000_000_000_000L) break;
        }
        while (index < values.length) values[index++] = random.nextLong();

        for (var bufferSize = 4; bufferSize <= 32; bufferSize++) {
            var bytes = new ByteArrayOutputStream();
            var output = new HtmlOutput(bytes, bufferSize);
            var expected = new StringBuilder();
            for (var value : values) {
                output.text("|");
                output.text(value);
                expected.append('|').append(value);
            }
            output.flush();
            assertEquals(expected.toString(), bytes.toString(StandardCharsets.UTF_8), "bufferSize=" + bufferSize);
        }
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

    private static String render(String value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.text(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String render(boolean value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.text(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String render(long value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        output.text(value);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
