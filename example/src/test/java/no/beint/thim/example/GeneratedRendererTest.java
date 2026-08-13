package no.beint.thim.example;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.example.generated.ExampleTemplates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedRendererTest {
    @Test
    void partitionsLargeRenderersBelowTheHotSpotHugeMethodThreshold() throws IOException {
        var resource = "/no/beint/thim/example/generated/no_beint_thim_example_page_LargePageThimRenderer.class";
        byte[] bytes;
        try (var input = getClass().getResourceAsStream(resource)) {
            bytes = input.readAllBytes();
        }
        var methods = ClassFile.of().parse(bytes).methods().stream()
                .filter(method -> method.methodName().stringValue().startsWith("render"))
                .toList();

        assertTrue(methods.size() > 2, "large fixture should exercise renderer partitioning");
        methods.forEach(method -> assertTrue(
                ((CodeAttribute) method.code().orElseThrow()).codeLength() < 8_000,
                () -> method.methodName().stringValue() + " exceeds HotSpot's huge-method threshold"));
    }

    @Test
    void escapesStaticAttributeValuesInGeneratedHtml() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);

        new ExampleTemplates().render(new HomeCtrl().home(), new RenderContext(Locale.ENGLISH, ""), output);
        output.flush();

        var html = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(html.contains(
                "data-config=\"{&quot;value&quot;:&quot;id&quot;,&quot;title&quot;:&quot;A&amp;B&quot;,&quot;markup&quot;:&quot;&lt;b&gt;&quot;}\""),
                html);
    }

    @Test
    void writesHtmlLangFromTheRequestLocaleWhenOmitted() throws IOException {
        var english = renderHome(Locale.ENGLISH);
        var norwegian = renderHome(Locale.forLanguageTag("nb"));

        assertTrue(english.contains("<html lang=\"en\">"), english);
        assertTrue(norwegian.contains("<html lang=\"nb\">"), norwegian);
    }

    private static String renderHome(Locale locale) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        new ExampleTemplates().render(new HomeCtrl().home(), new RenderContext(locale, ""), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
