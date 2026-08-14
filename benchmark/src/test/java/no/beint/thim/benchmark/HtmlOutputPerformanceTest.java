package no.beint.thim.benchmark;

import java.io.IOException;

import no.beint.thim.HtmlOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlOutputPerformanceTest {
    private static final String ASCII = "The catalog has 50 items and a stable heading.\n".repeat(40);
    private static final String ESCAPE = "A & B <tag> 'quote' \"value\" and more & more.\n".repeat(20);
    private static final String UNICODE = "Blåbærsyltetøy på skjerf — café résumé 日本語.\n".repeat(20);
    private static final byte[] RAW = "<section class=\"static\">plain utf-8 bytes</section>\n".repeat(80)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void escapingStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("html.escape", output -> output.text(ESCAPE));
    }

    @Test
    void asciiTextStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("html.ascii", output -> output.text(ASCII));
    }

    @Test
    void unicodeTextStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("html.unicode", output -> output.text(UNICODE));
    }

    @Test
    void integerTextStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("html.integer", output -> {
            for (var value = 0; value < 64; value++) {
                output.text(1_000_000_000L + value);
            }
        });
    }

    @Test
    void rawCopyStaysUnderTheCommittedCeiling() {
        assertUnderCeiling("html.raw", output -> output.raw(RAW, 0, RAW.length));
    }

    private static void assertUnderCeiling(String name, OutputAction action) {
        var sink = new RecycledOutputStream();
        var output = new HtmlOutput(sink);
        var result = Measure.run(name, 60, 80, 50, () -> {
            sink.reset();
            action.write(output);
            output.flush();
            assertTrue(sink.size() > 0);
        });
        assertTrue(
                result.medianNanosPerOp() <= Baselines.ceiling(name),
                () -> name + " median " + result.medianNanosPerOp() + " ns/op exceeds ceiling "
                        + Baselines.ceiling(name) + " ns/op"
        );
    }

    @FunctionalInterface
    private interface OutputAction {
        void write(HtmlOutput output) throws IOException;
    }
}
