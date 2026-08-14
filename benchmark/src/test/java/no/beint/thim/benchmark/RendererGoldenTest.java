package no.beint.thim.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.benchmark.generated.BenchmarkTemplates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererGoldenTest {
    private static final Locale NORWEGIAN = Locale.forLanguageTag("nb");

    @Test
    void inboxEnglishMatchesTheCommittedPage() throws IOException {
        var actual = html(Fixtures.inbox(), Locale.ENGLISH);
        assertEquals(golden("inbox-en.html", actual), actual);
    }

    @Test
    void inboxNorwegianOneAndManyMatchTheCommittedPages() throws IOException {
        var one = html(Fixtures.inbox(1, 1), NORWEGIAN);
        var many = html(Fixtures.inbox(), NORWEGIAN);
        assertEquals(golden("inbox-nb-one.html", one), one);
        assertEquals(golden("inbox-nb.html", many), many);
    }

    @Test
    void catalogEnglishMatchesTheCommittedPage() throws IOException {
        var actual = html(Fixtures.catalog(), Locale.ENGLISH);
        assertEquals(golden("catalog-en.html", actual), actual);
    }

    @Test
    void plainPageMatchesTheCommittedPage() throws IOException {
        var actual = html(Fixtures.plain(), Locale.ENGLISH);
        assertEquals(golden("plain.html", actual), actual);
    }

    @Test
    void tinyAndDefaultBuffersWriteTheSameInboxBytes() throws IOException {
        var model = Fixtures.inbox();
        assertEquals(html(model, Locale.ENGLISH, 16 * 1024), html(model, Locale.ENGLISH, 4));
    }

    private static String golden(String name, String actual) throws IOException {
        try (var input = RendererGoldenTest.class.getResourceAsStream("/golden/" + name)) {
            if (input != null && !Boolean.getBoolean("thim.goldens.update")) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        var path = java.nio.file.Path.of("src/test/resources/golden", name);
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, actual);
        if (Boolean.getBoolean("thim.goldens.update")) {
            return actual;
        }
        throw new AssertionError("Wrote missing golden " + path + "; re-run tests to compare");
    }

    private static String html(Object model, Locale locale) throws IOException {
        return html(model, locale, 16 * 1024);
    }

    private static String html(Object model, Locale locale, int bufferSize) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes, bufferSize);
        new BenchmarkTemplates().render(model, new RenderContext(locale, ""), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
