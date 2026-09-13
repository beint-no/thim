package no.beint.thim.benchmark;

import no.beint.thim.compiler.ComponentLinker;
import no.beint.thim.compiler.Node;
import no.beint.thim.compiler.TemplateParser;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TemplateCompilerBenchmark {
    @Param({"synthetic"})
    public String templatesDirectory;

    @Param({"100", "1000"})
    public int elements;

    private Map<String, String> sources;
    private Map<String, List<Node>> parsed;
    private List<String> pages;

    @Setup
    public void setup() throws IOException {
        sources = new LinkedHashMap<>();
        if (templatesDirectory.equals("synthetic")) {
            sources.put("card", "<ui:component name=\"card\" props=\"example.Card\"><section class=\"card\"><h2 th:text=\"${title}\"></h2></section></ui:component>");
            sources.put("page", "<main>\n" +
                    "<ui:card props=\"${card}\"></ui:card>\n".repeat(elements) + "</main>\n");
        } else {
            var root = Path.of(templatesDirectory);
            try (var files = Files.walk(root)) {
                for (var file : files.filter(path -> path.toString().endsWith(".html")).sorted().toList()) {
                    var name = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                    sources.put(name.substring(0, name.length() - ".html".length()), Files.readString(file));
                }
            }
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("No HTML templates in " + templatesDirectory);
        parsed = new LinkedHashMap<>();
        sources.forEach((name, source) -> parsed.put(name, new TemplateParser(name, source).parse()));
        pages = parsed.entrySet().stream().filter(entry -> !new ComponentLinker(parsed).isComponent(entry.getKey())).map(Map.Entry::getKey).toList();
    }

    @Benchmark
    public void parseTemplates(Blackhole blackhole) {
        sources.forEach((name, source) -> blackhole.consume(new TemplateParser(name, source).parse()));
    }

    @Benchmark
    public void linkComponents(Blackhole blackhole) {
        var expander = new ComponentLinker(parsed);
        for (var page : pages) blackhole.consume(expander.expand(parsed.get(page)));
        blackhole.consume(expander.unusedComponents());
    }

}
