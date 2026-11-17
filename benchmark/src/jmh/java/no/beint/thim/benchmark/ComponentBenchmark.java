package no.beint.thim.benchmark;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import no.beint.thim.TrustedUrl;
import no.beint.thim.benchmark.page.ComponentItem;
import no.beint.thim.benchmark.page.ComponentPage;
import no.beint.thim.benchmark.page.InlinePage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ComponentBenchmark {
    @Param({"1", "100", "1000"}) public int count;
    private RenderSession session;
    private ComponentPage component;
    private InlinePage inline;

    @Setup public void setup() {
        session = new RenderSession();
        List<ComponentItem> items = IntStream.range(0, count).mapToObj(i ->
                new ComponentItem("Item & <" + i + ">", new TrustedUrl("/item/" + i), i % 2 == 0)).toList();
        component = new ComponentPage(items);
        inline = new InlinePage(items);
    }
    @Benchmark public void components(Blackhole bh) throws Exception { bh.consume(session.render(component, Locale.ENGLISH)); }
    @Benchmark public void inline(Blackhole bh) throws Exception { bh.consume(session.render(inline, Locale.ENGLISH)); }
}
