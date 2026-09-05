package no.beint.thim.benchmark;

import no.beint.thim.CompiledMessage;
import no.beint.thim.benchmark.generated.BenchmarkMessages;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class MessageBenchmark {
    @Param({"en", "nb"})
    public String language;

    private Locale locale;
    private CompiledMessage constant;
    private CompiledMessage parameterized;

    @Setup
    public void setup() {
        locale = Locale.forLanguageTag(language);
        constant = BenchmarkMessages.Catalog.title();
        parameterized = BenchmarkMessages.Catalog.count("50");
    }

    @Benchmark
    public String constantMessage() {
        return constant.resolve(locale);
    }

    @Benchmark
    public String constantReference() {
        return BenchmarkMessages.resolveReference(BenchmarkMessages.Catalog.titleReference, locale);
    }

    @Benchmark
    public String parameterizedMessage() {
        return parameterized.resolve(locale);
    }
}
