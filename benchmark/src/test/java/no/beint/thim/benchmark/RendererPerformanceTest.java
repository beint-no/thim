package no.beint.thim.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.benchmark.generated.BenchmarkMessages;
import no.beint.thim.benchmark.generated.BenchmarkTemplates;
import no.beint.thim.benchmark.page.CatalogPage;
import no.beint.thim.benchmark.page.InboxPage;
import no.beint.thim.benchmark.page.PlainPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererPerformanceTest {
    private static final Locale NORWEGIAN = Locale.forLanguageTag("nb");
    private static final InboxPage INBOX = Fixtures.inbox();
    private static final CatalogPage CATALOG = Fixtures.catalog();
    private static final CatalogPage SINGLE = Fixtures.catalog(1);
    private static final PlainPage PLAIN = Fixtures.plain();

    @BeforeAll
    static void compilePathIsWarm() throws IOException {
        html(INBOX, Locale.ENGLISH);
        html(INBOX, NORWEGIAN);
        html(CATALOG, Locale.ENGLISH);
        html(PLAIN, Locale.ENGLISH);
    }

    @Test
    void inboxEnglishContainsCompiledPluralAndEscapesDynamicText() throws IOException {
        var html = html(INBOX, Locale.ENGLISH);
        assertTrue(html.contains("<html lang=\"en\">"), html);
        assertTrue(html.contains("3 unread messages"), html);
        assertTrue(html.contains("Subject 0: invoices &amp; &lt;updates&gt;"), html);
        assertTrue(html.contains(">New<"), html);
    }

    @Test
    void inboxNorwegianSelectsTheCompiledCatalog() throws IOException {
        var one = html(Fixtures.inbox(1, 1), NORWEGIAN);
        var many = html(INBOX, NORWEGIAN);
        assertTrue(one.contains("<html lang=\"nb\">"), one);
        assertTrue(one.contains("Én ulest melding"), one);
        assertTrue(one.contains(">Ny<"), one);
        assertTrue(many.contains("3 uleste meldinger"), many);
        assertTrue(many.contains("Innboks"), many);
    }

    @Test
    void backendMessagesUseTypedArgumentsAndTheCompiledLocaleCatalog() {
        assertEquals("One unread message", BenchmarkMessages.Inbox.unread(1).resolve(Locale.ENGLISH));
        assertEquals("3 uleste meldinger", BenchmarkMessages.Inbox.unread(3).resolve(NORWEGIAN));
        assertEquals("5 items", BenchmarkMessages.Catalog.count("5").resolve(Locale.ENGLISH));
        assertEquals("Thim in backend", BenchmarkMessages.Catalog.echo("Thim", "backend").resolve(Locale.ENGLISH));
        assertEquals("Welcome, <Ada>", BenchmarkMessages.Catalog.salutation("GUEST", "<Ada>").resolve(Locale.ENGLISH));
        assertEquals("Ny", BenchmarkMessages.Inbox.new_().resolve(Locale.forLanguageTag("nb-NO")));
    }

    @Test
    void catalogRendersItemsUrlsAndFeaturedCopy() throws IOException {
        var html = html(CATALOG, Locale.ENGLISH);
        assertTrue(html.contains("50 items"), html);
        assertTrue(html.contains("href=\"/item/1\""), html);
        assertTrue(html.contains("ampersand &amp; a &lt;tag&gt;"), html);
        assertTrue(html.contains(">Featured<"), html);
        var norwegian = html(CATALOG, NORWEGIAN);
        assertTrue(norwegian.contains("50 varer"), norwegian);
        assertTrue(norwegian.contains("Fremhevet"), norwegian);
    }

    @Test
    void plainPageKeepsStaticCopyAndTheTitle() throws IOException {
        var html = html(PLAIN, Locale.ENGLISH);
        assertTrue(html.contains("<title>Thim render benchmark</title>"), html);
        assertTrue(html.contains("static-copy fixture"), html);
        assertEquals(html.indexOf("Why this page exists"), html.lastIndexOf("Why this page exists"));
    }

    @Test
    void inboxEnglishStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("inbox.en", session -> session.render(INBOX, Locale.ENGLISH));
    }

    @Test
    void inboxNorwegianStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("inbox.nb", session -> session.render(INBOX, NORWEGIAN));
    }

    @Test
    void catalogOfFiftyStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("catalog.50", session -> session.render(CATALOG, Locale.ENGLISH));
    }

    @Test
    void singleItemCatalogStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("catalog.1", session -> session.render(SINGLE, Locale.ENGLISH));
    }

    @Test
    void plainPageStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("plain", session -> session.render(PLAIN, Locale.ENGLISH));
    }

    @Test
    void catalogCostScalesRoughlyWithItemCount() {
        var session = new RenderSession();
        // Keep each timed sample large enough that scheduler and timer noise do not
        // dominate the ratio, especially for the sub-microsecond single-item render.
        var one = Measure.run("catalog.1", 40, 60, 2_000, () -> session.render(SINGLE, Locale.ENGLISH));
        var fifty = Measure.run("catalog.50", 40, 60, 100, () -> session.render(CATALOG, Locale.ENGLISH));
        var scale = fifty.medianNanosPerOp() / (double) Math.max(1, one.medianNanosPerOp());
        System.out.println("[thim-bench] catalog.scale  " + String.format(java.util.Locale.ROOT, "%.2fx", scale));
        assertTrue(
                scale < Baselines.ceiling("catalog.scale.max"),
                () -> "catalog(50) was " + scale + "× catalog(1); expected less than "
                        + Baselines.ceiling("catalog.scale.max")
        );
    }

    private static void assertUnderCeiling(String name, SessionAction action) {
        var session = new RenderSession();
        var result = Measure.run(name, 50, 80, 40, () -> action.run(session));
        assertTrue(
                result.medianNanosPerOp() <= Baselines.ceiling(name),
                () -> name + " median " + result.medianNanosPerOp() + " ns/op exceeds ceiling "
                        + Baselines.ceiling(name) + " ns/op"
        );
    }

    private static String html(Object model, Locale locale) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        new BenchmarkTemplates().render(model, new RenderContext(locale, ""), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface SessionAction {
        void run(RenderSession session) throws Exception;
    }
}
