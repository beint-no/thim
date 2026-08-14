package no.beint.thim.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import no.beint.thim.HtmlOutput;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HtmlOutputBenchmark {
    private RecycledOutputStream sink;
    private HtmlOutput output;
    private String ascii;
    private String escape;
    private String unicode;
    private byte[] raw;

    @Setup
    public void setup() {
        sink = new RecycledOutputStream();
        output = new HtmlOutput(sink);
        ascii = "The catalog has 50 items and a stable heading.\n".repeat(40);
        escape = "A & B <tag> 'quote' \"value\" and more & more.\n".repeat(20);
        unicode = "Blåbærsyltetøy på skjerf — café résumé 日本語.\n".repeat(20);
        raw = "<section class=\"static\">plain utf-8 bytes</section>\n".repeat(80)
                .getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public void asciiText(Blackhole blackhole) throws IOException {
        sink.reset();
        output.text(ascii);
        output.flush();
        blackhole.consume(sink.size());
    }

    @Benchmark
    public void escapingText(Blackhole blackhole) throws IOException {
        sink.reset();
        output.text(escape);
        output.flush();
        blackhole.consume(sink.size());
    }

    @Benchmark
    public void unicodeText(Blackhole blackhole) throws IOException {
        sink.reset();
        output.text(unicode);
        output.flush();
        blackhole.consume(sink.size());
    }

    @Benchmark
    public void integers(Blackhole blackhole) throws IOException {
        sink.reset();
        for (var value = 0; value < 64; value++) {
            output.text(1_000_000_000L + value);
        }
        output.flush();
        blackhole.consume(sink.size());
    }

    @Benchmark
    public void rawCopy(Blackhole blackhole) throws IOException {
        sink.reset();
        output.raw(raw, 0, raw.length);
        output.flush();
        blackhole.consume(sink.size());
    }
}
