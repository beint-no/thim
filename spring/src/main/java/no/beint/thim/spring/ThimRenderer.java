package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.RequestDataValues;
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
    private final List<ThimRenderObserver> observers;

    public ThimRenderer() {
        this((RequestDataValueProcessor) null);
    }

    public ThimRenderer(RequestDataValueProcessor requestDataValueProcessor) {
        this(requestDataValueProcessor, List.of());
    }

    public ThimRenderer(
            RequestDataValueProcessor requestDataValueProcessor,
            List<ThimRenderObserver> observers
    ) {
        this(
                ServiceLoader.load(TemplateSet.class).stream().map(ServiceLoader.Provider::get).toList(),
                requestDataValueProcessor,
                observers
        );
    }

    public ThimRenderer(List<TemplateSet> templates) {
        this(templates, null);
    }

    public ThimRenderer(List<TemplateSet> templates, RequestDataValueProcessor requestDataValueProcessor) {
        this(templates, requestDataValueProcessor, List.of());
    }

    public ThimRenderer(
            List<TemplateSet> templates,
            RequestDataValueProcessor requestDataValueProcessor,
            List<ThimRenderObserver> observers
    ) {
        this.templates = List.copyOf(templates);
        this.requestDataValueProcessor = requestDataValueProcessor;
        this.observers = List.copyOf(observers);
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
        if (observers.isEmpty()) {
            renderResponse(model, request, response);
            return;
        }
        var render = new ThimRender(model.getClass(), ThimRender.Mode.SERVLET_RESPONSE, request.getRequestURI());
        started(render);
        try {
            renderResponse(model, request, response);
        } catch (IOException | RuntimeException | Error failure) {
            failed(render, failure);
            throw failure;
        }
        succeeded(render);
    }

    private void renderResponse(Object model, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var templateSet = templateSetFor(model);
        var locale = RequestContextUtils.getLocale(request);
        var contextPath = request.getContextPath();
        var requestDataValues = templateSet.usesRequestDataValues(model.getClass())
                ? SpringRequestDataValues.create(request, response, requestDataValueProcessor)
                : RequestDataValues.NONE;
        var context = new RenderContext(locale, contextPath, requestDataValues);
        var body = new ByteArrayOutputStream(8 * 1024);
        var output = new HtmlOutput(body, OUTPUT_BUFFER_SIZE);
        templateSet.render(model, context, output);
        output.flush();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html;charset=UTF-8");
        response.setContentLength(body.size());
        body.writeTo(response.getOutputStream());
    }

    public String renderToString(Object model, Locale locale) throws IOException {
        return renderToString(model, locale, "");
    }

    public String renderToString(Object model, Locale locale, String contextPath) throws IOException {
        if (observers.isEmpty()) {
            return renderString(model, locale, contextPath);
        }
        var render = new ThimRender(model.getClass(), ThimRender.Mode.STRING, "");
        started(render);
        try {
            var result = renderString(model, locale, contextPath);
            succeeded(render);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            failed(render, failure);
            throw failure;
        }
    }

    private String renderString(Object model, Locale locale, String contextPath) throws IOException {
        var templateSet = templateSetFor(model);
        var bytes = new ByteArrayOutputStream(4096);
        var output = new HtmlOutput(bytes, OUTPUT_BUFFER_SIZE);
        templateSet.render(model, new RenderContext(locale, contextPath), output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void started(ThimRender render) {
        for (var observer : observers) {
            try {
                observer.started(render);
            } catch (Throwable ignored) {
            }
        }
    }

    private void succeeded(ThimRender render) {
        for (var observer : observers) {
            try {
                observer.succeeded(render);
            } catch (Throwable ignored) {
            }
        }
    }

    private void failed(ThimRender render, Throwable failure) {
        for (var observer : observers) {
            try {
                observer.failed(render, failure);
            } catch (Throwable ignored) {
            }
        }
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
