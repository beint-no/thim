package no.beint.thim.benchmark;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.IntStream;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TrustedUrl;
import no.beint.thim.benchmark.generated.BenchmarkTemplates;
import no.beint.thim.benchmark.page.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPerformanceTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 100, 1000})
    void componentAndInlineHtmlAreByteIdentical(int count) throws Exception {
        var items = IntStream.range(0, count).mapToObj(i -> new ComponentItem("Item & <" + i + ">", new TrustedUrl("/item/" + i), i % 2 == 0)).toList();
        var html = html(new ComponentPage(items));
        assertEquals(html(new InlinePage(items)), html);
        assertFalse(html.contains("ui:"));
    }
    @ParameterizedTest @ValueSource(ints = {1, 100, 1000})
    void compiledCompositionStaysCloseToDirectHtml(int count) {
        var items = IntStream.range(0, count).mapToObj(i -> new ComponentItem("Item " + i, new TrustedUrl("/item/" + i), true)).toList();
        var component = new ComponentPage(items);
        var inline = new InlinePage(items);
        var session = new RenderSession();
        var baseline = Measure.run("inline." + count, 40, 60, 200, () -> session.render(inline, Locale.ENGLISH));
        var result = Measure.runUnderCeiling("component." + count, baseline.medianNanosPerOp() * 2 + 500, 40, 60, 200,
                () -> session.render(component, Locale.ENGLISH));
        assertTrue(result.medianNanosPerOp() < baseline.medianNanosPerOp() * 2 + 500, result.toString());
    }
    private String html(Object page) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new HtmlOutput(bytes, 32);
        new BenchmarkTemplates().render(page, new RenderContext(Locale.ENGLISH, "/app"), out);
        out.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
