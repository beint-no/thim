package no.beint.thim.benchmark;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import no.beint.thim.benchmark.page.CatalogPage;
import no.beint.thim.benchmark.page.InboxPage;
import no.beint.thim.benchmark.page.PlainPage;
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
public class RendererBenchmark {
    private RenderSession session;
    private InboxPage inbox;
    private CatalogPage catalog;
    private PlainPage plain;

    @Setup
    public void setup() {
        session = new RenderSession();
        inbox = Fixtures.inbox();
        catalog = Fixtures.catalog();
        plain = Fixtures.plain();
    }

    @Benchmark
    public void inboxEnglish(Blackhole blackhole) throws IOException {
        blackhole.consume(session.render(inbox, Locale.ENGLISH));
    }

    @Benchmark
    public void inboxNorwegian(Blackhole blackhole) throws IOException {
        blackhole.consume(session.render(inbox, Locale.forLanguageTag("nb")));
    }

    @Benchmark
    public void catalogFifty(Blackhole blackhole) throws IOException {
        blackhole.consume(session.render(catalog, Locale.ENGLISH));
    }

    @Benchmark
    public void plainStatic(Blackhole blackhole) throws IOException {
        blackhole.consume(session.render(plain, Locale.ENGLISH));
    }
}
