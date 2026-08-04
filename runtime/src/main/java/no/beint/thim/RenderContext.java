package no.beint.thim;

import java.util.Locale;
import java.util.Objects;

public record RenderContext(Locale locale, String contextPath, RequestDataValues requestDataValues) {
    public RenderContext(Locale locale, String contextPath) {
        this(locale, contextPath, RequestDataValues.NONE);
    }

    public RenderContext {
        Objects.requireNonNull(locale);
        Objects.requireNonNull(contextPath);
        Objects.requireNonNull(requestDataValues);
    }
}
