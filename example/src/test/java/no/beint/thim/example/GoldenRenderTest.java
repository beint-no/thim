package no.beint.thim.example;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.example.generated.ExampleTemplates;
import no.beint.thim.example.page.DerivedPage;
import no.beint.thim.example.page.LargePage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendered HTML for every example page must stay byte-identical across compiler changes
 * that only reorganize generated code. The goldens were recorded with the 0.11.2 compiler;
 * re-record deliberately with {@code -Dthim.golden.record=true} when output is meant to change.
 */
class GoldenRenderTest {
    private static final Path GOLDEN_DIRECTORY = Path.of("src/test/resources/golden");

    @Test
    void renderedPagesMatchTheRecordedGoldens() throws IOException {
        var pages = new LinkedHashMap<String, Object>();
        pages.put("home", new HomeCtrl().home());
        pages.put("home-with-errors", new HomeCtrl().feedback(new FeedbackForm("", "Message & <text>", 4, true, "high", "bug")));
        pages.put("large", new LargePage("Large & <value>"));
        pages.put("derived", new DerivedPage("source"));
        var record = Boolean.getBoolean("thim.golden.record");
        var rendered = new LinkedHashMap<String, String>();
        for (var page : pages.entrySet()) {
            for (var locale : new Locale[] {Locale.ENGLISH, Locale.forLanguageTag("nb"), Locale.GERMAN}) {
                rendered.put(page.getKey() + "." + locale.toLanguageTag() + ".html", render(page.getValue(), locale));
            }
        }
        if (record) {
            Files.createDirectories(GOLDEN_DIRECTORY);
            for (var entry : rendered.entrySet()) {
                Files.writeString(GOLDEN_DIRECTORY.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        for (Map.Entry<String, String> entry : rendered.entrySet()) {
            var golden = GOLDEN_DIRECTORY.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(golden), "missing golden " + golden + "; record with -Dthim.golden.record=true");
            assertEquals(Files.readString(golden, StandardCharsets.UTF_8), entry.getValue(), entry.getKey());
        }
    }

    private static String render(Object page, Locale locale) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes, 64);
        new ExampleTemplates().render(page, new RenderContext(locale, "/app"), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
