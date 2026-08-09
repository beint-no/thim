package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TemplateSet;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

public final class ThimRenderer {
    private static final int OUTPUT_BUFFER_SIZE = 1024;

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
        for (var templateSet : templates) {
            if (templateSet.supports(modelType)) {
                return true;
            }
        }
        return false;
    }

    public boolean supportsReturnType(Class<?> returnType) {
        for (var templateSet : templates) {
            if (templateSet.supportsReturnType(returnType)) {
                return true;
            }
        }
        return false;
    }

    public void render(Object model, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var templateSet = templateSetFor(model);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html");
        var output = new HtmlOutput(response.getOutputStream(), OUTPUT_BUFFER_SIZE);
        var locale = RequestContextUtils.getLocale(request);
        var contextPath = request.getContextPath();
        var context = requestDataValueProcessor == null
                ? new RenderContext(locale, contextPath)
                : new RenderContext(locale, contextPath, new SpringRequestDataValues(request, requestDataValueProcessor));
        templateSet.render(
                model,
                context,
                output);
        output.flush();
    }

    public String renderToString(Object model, Locale locale) throws IOException {
        return renderToString(model, locale, "");
    }

    public String renderToString(Object model, Locale locale, String contextPath) throws IOException {
        var templateSet = templateSetFor(model);
        var bytes = new ByteArrayOutputStream(4096);
        var output = new HtmlOutput(bytes, OUTPUT_BUFFER_SIZE);
        templateSet.render(model, new RenderContext(locale, contextPath), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private TemplateSet templateSetFor(Object model) {
        var modelType = model.getClass();
        if (templates.size() == 1) {
            var templateSet = templates.getFirst();
            if (templateSet.supports(modelType)) {
                return templateSet;
            }
        } else {
            for (var templateSet : templates) {
                if (templateSet.supports(modelType)) {
                    return templateSet;
                }
            }
        }
        throw new IllegalArgumentException("No compiled template for " + modelType.getName());
    }
}
