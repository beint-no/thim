package no.beint.thim;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Renders compiled page models through one or more {@link TemplateSet} instances without
 * requiring a web framework.
 */
public final class TemplateRenderer {
    private static final int OUTPUT_BUFFER_SIZE = 1024;

    private final List<TemplateSet> templates;

    public TemplateRenderer(List<TemplateSet> templates) {
        this.templates = List.copyOf(Objects.requireNonNull(templates, "templates"));
    }

    public static TemplateRenderer fromServiceLoader() {
        return new TemplateRenderer(
                ServiceLoader.load(TemplateSet.class).stream().map(ServiceLoader.Provider::get).toList()
        );
    }

    public static TemplateRenderer fromServiceLoader(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        return new TemplateRenderer(
                ServiceLoader.load(TemplateSet.class, classLoader).stream().map(ServiceLoader.Provider::get).toList()
        );
    }

    public static TemplateRenderer of(TemplateSet... templates) {
        Objects.requireNonNull(templates, "templates");
        return new TemplateRenderer(List.of(templates));
    }

    public boolean supports(Class<?> modelType) {
        Objects.requireNonNull(modelType, "modelType");
        for (var templateSet : templates) {
            if (templateSet.supports(modelType)) {
                return true;
            }
        }
        return false;
    }

    public boolean supportsReturnType(Class<?> returnType) {
        Objects.requireNonNull(returnType, "returnType");
        for (var templateSet : templates) {
            if (templateSet.supportsReturnType(returnType)) {
                return true;
            }
        }
        return false;
    }

    public String renderToString(Object model, Locale locale) throws IOException {
        return renderToString(model, locale, "");
    }

    public String renderToString(Object model, Locale locale, String contextPath) throws IOException {
        return renderToString(model, new RenderContext(locale, contextPath));
    }

    public String renderToString(Object model, RenderContext context) throws IOException {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        var templateSet = templateSetFor(model);
        var bytes = new ByteArrayOutputStream(4096);
        var output = new HtmlOutput(bytes, OUTPUT_BUFFER_SIZE);
        templateSet.render(model, context, output);
        output.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    public void render(Object model, Locale locale, OutputStream out) throws IOException {
        render(model, locale, "", out);
    }

    public void render(Object model, Locale locale, String contextPath, OutputStream out) throws IOException {
        render(model, new RenderContext(locale, contextPath), out);
    }

    public void render(Object model, RenderContext context, OutputStream out) throws IOException {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(out, "out");
        var templateSet = templateSetFor(model);
        var output = new HtmlOutput(out, OUTPUT_BUFFER_SIZE);
        templateSet.render(model, context, output);
        output.flush();
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
