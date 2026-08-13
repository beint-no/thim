package no.beint.thim;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
