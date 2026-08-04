package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TemplateSet;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

public final class ThimRenderer {
    private final List<TemplateSet> templates;
    private final RequestDataValueProcessor requestDataValueProcessor;

    public ThimRenderer() {
        this((RequestDataValueProcessor) null);
    }

    public ThimRenderer(RequestDataValueProcessor requestDataValueProcessor) {
        this(ServiceLoader.load(TemplateSet.class).stream().map(ServiceLoader.Provider::get).toList(), requestDataValueProcessor);
    }

    public ThimRenderer(List<TemplateSet> templates) {
        this(templates, null);
    }

    public ThimRenderer(List<TemplateSet> templates, RequestDataValueProcessor requestDataValueProcessor) {
        this.templates = List.copyOf(templates);
        this.requestDataValueProcessor = requestDataValueProcessor;
    }

    public boolean supports(Class<?> modelType) {
        return templates.stream().anyMatch(templateSet -> templateSet.supports(modelType));
    }

    public boolean supportsReturnType(Class<?> returnType) {
        return templates.stream().anyMatch(templateSet -> templateSet.supportsReturnType(returnType));
    }

    public void render(Object model, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var templateSet = templates.stream()
                .filter(candidate -> candidate.supports(model.getClass()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No compiled template for " + model.getClass().getName()));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html");
        var output = new HtmlOutput(response.getOutputStream());
        templateSet.render(
                model,
                new RenderContext(
                        RequestContextUtils.getLocale(request),
                        request.getContextPath(),
                        new SpringRequestDataValues(request, requestDataValueProcessor)),
                output);
        output.flush();
    }

    public String renderToString(Object model, Locale locale) throws IOException {
        return renderToString(model, locale, "");
    }

    public String renderToString(Object model, Locale locale, String contextPath) throws IOException {
        var templateSet = templates.stream()
                .filter(candidate -> candidate.supports(model.getClass()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No compiled template for " + model.getClass().getName()));
        var bytes = new ByteArrayOutputStream();
        var output = new HtmlOutput(bytes);
        templateSet.render(model, new RenderContext(locale, contextPath), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
