package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TemplateSet;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;

public final class ThimRenderer {
    private final List<TemplateSet> templates;

    public ThimRenderer() {
        this(ServiceLoader.load(TemplateSet.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    public ThimRenderer(List<TemplateSet> templates) {
        this.templates = List.copyOf(templates);
    }

    public boolean supports(Class<?> modelType) {
        return templates.stream().anyMatch(templateSet -> templateSet.supports(modelType));
    }

    public void render(Object model, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var templateSet = templates.stream()
                .filter(candidate -> candidate.supports(model.getClass()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No compiled template for " + model.getClass().getName()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html");
        var output = new HtmlOutput(response.getOutputStream());
        templateSet.render(model, new RenderContext(RequestContextUtils.getLocale(request), request.getContextPath()), output);
        output.flush();
    }
}
