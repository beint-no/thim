package no.beint.thim.example;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.example.generated.ExampleTemplates;
import no.beint.thim.example.page.HomePage;
import no.beint.thim.example.page.LargePage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.CodeAttribute;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedRendererTest {
    @Test
    void reportsRequestDataUsagePerCompiledModel() {
        var templates = new ExampleTemplates();

        assertTrue(templates.usesRequestDataValues(HomePage.class));
        assertFalse(templates.usesRequestDataValues(LargePage.class));
        assertFalse(templates.usesRequestDataValues(String.class));
    }

    @Test
    void dispatchesOnExactModelClassesAndSupertypesOfReturnTypes() throws IOException {
        var templates = new ExampleTemplates();

        assertTrue(templates.supports(HomePage.class));
        assertTrue(templates.supports(LargePage.class));
        assertFalse(templates.supports(Object.class));
        assertFalse(templates.supports(String.class));

        assertTrue(templates.supportsReturnType(HomePage.class));
        assertTrue(templates.supportsReturnType(java.io.Serializable.class) == java.io.Serializable.class.isAssignableFrom(HomePage.class));
        assertFalse(templates.supportsReturnType(Object.class));
        assertFalse(templates.supportsReturnType(String.class));

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> templates.render("not a page", new RenderContext(Locale.ENGLISH, ""), new HtmlOutput(new ByteArrayOutputStream()))
        );
        assertEquals("No compiled template for java.lang.String", failure.getMessage());
    }

    @Test
    void writesTheMessageUsageManifestWithoutGeneratingFactories() throws IOException {
        assertNull(getClass().getResource("/no/beint/thim/example/generated/ExampleMessages.class"));

        java.util.List<String> lines;
        try (var manifest = getClass().getResourceAsStream(
                "/META-INF/thim/messages/no_beint_thim_example_generated_ExampleMessages.usage")) {
            assertNotNull(manifest);
            lines = new String(manifest.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertEquals("thim-message-usage\t1", lines.getFirst());
        assertTrue(lines.contains("enforce\ttrue"), lines.toString());
        assertTrue(lines.contains("template\t" + encoded("home.title")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("definition\t" + encoded("home.title") + "\t")), lines.toString());
    }

    private static String encoded(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

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
