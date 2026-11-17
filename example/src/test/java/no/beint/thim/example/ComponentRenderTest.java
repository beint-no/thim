package no.beint.thim.example;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TrustedUrl;
import no.beint.thim.example.generated.ExampleTemplates;
import no.beint.thim.example.page.*;
import no.beint.thim.example.ui.LinkProps;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRenderTest {
    private String render(boolean disabled) throws Exception {
        var page = new ComponentsPage("Caller & <title>", new CardProps("Component & <title>"),
                new ButtonProps(disabled, ButtonAppearance.SECONDARY),
                List.of(new LinkProps(new TrustedUrl("/one"), "First & <link>"), new LinkProps(new TrustedUrl("/two"), "Second")),
                new ComponentForm("Ada & <Lovelace>"));
        var bytes = new ByteArrayOutputStream();
        var out = new HtmlOutput(bytes, 16);
        new ExampleTemplates().render(page, new RenderContext(Locale.ENGLISH, "/app"), out);
        out.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void slotsUseCallerScopeAndFallbackUsesProps() throws Exception {
        var html = render(false);
        assertTrue(html.contains("<h1>Caller &amp; &lt;title&gt;</h1>"), html);
        assertTrue(html.contains("<h2>Component &amp; &lt;title&gt;</h2>"), html);
        assertTrue(html.contains("<span>Caller &amp; &lt;title&gt;</span>"), html);
    }
    @Test void propsKeepEnumAndBooleanTypes() throws Exception {
        assertTrue(render(true).contains("<button type=\"button\" class=\"secondary\" disabled>"));
        assertTrue(render(false).contains("<button type=\"button\" class=\"secondary\">"));
    }
    @Test void recordPropsAndLoopBindingsProduceSafeContextualUrls() throws Exception {
        var html = render(false);
        assertTrue(html.contains("href=\"/app/one\">First &amp; &lt;link&gt;</a>"), html);
        assertTrue(html.contains("href=\"/app/two\">Second</a>"), html);
    }
    @Test void nativeFieldsKeepTheCallersFormBinding() throws Exception {
        var html = render(false);
        assertTrue(html.contains("id=\"name\" name=\"name\" value=\"Ada &amp; &lt;Lovelace&gt;\""), html);
    }
    @Test void partitionedRenderersCapturePropsAndCallerLoopBindings() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new HtmlOutput(bytes);
        new ExampleTemplates().render(new LargeComponentPage("Caller", List.of(new CardProps("First"), new CardProps("Second"))),
                new RenderContext(Locale.ENGLISH, ""), out);
        out.flush();
        var html = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(300, html.split("<b>First</b>", -1).length - 1);
        assertEquals(300, html.split("<b>Second</b>", -1).length - 1);
        assertEquals(600, html.split("<i>Caller</i>", -1).length - 1);
        assertTrue(html.contains("<em>First</em>"), html);
        assertTrue(html.contains("<em>Second</em>"), html);
    }

    @Test void componentAndSlotSyntaxHasNoBrowserFootprint() throws Exception {
        var html = render(false);
        assertFalse(html.contains("ui:"), html);
        assertFalse(html.contains("th:"), html);
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<th:block"), html);
    }
}
