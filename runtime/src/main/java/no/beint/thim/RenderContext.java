package no.beint.thim;

import java.util.Locale;
import java.util.Objects;

public record RenderContext(Locale locale, String contextPath) {
    public RenderContext {
        Objects.requireNonNull(locale);
        Objects.requireNonNull(contextPath);
    }
}
