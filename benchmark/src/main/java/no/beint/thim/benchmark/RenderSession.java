package no.beint.thim.benchmark;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TemplateSet;
import no.beint.thim.benchmark.generated.BenchmarkTemplates;

public final class RenderSession {
    private final TemplateSet templates = new BenchmarkTemplates();
    private final RecycledOutputStream sink = new RecycledOutputStream();
    private final HtmlOutput output = new HtmlOutput(sink);

    public int render(Object model, Locale locale) throws IOException {
        Objects.requireNonNull(model);
        sink.reset();
        templates.render(model, new RenderContext(locale, ""), output);
        output.flush();
        return sink.size();
    }
}
