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

    /**
     * Called by generated renderers for every dynamic {@link TrustedUrl} value; it has no
     * source-level callers in this repository. Application-relative values (a single leading
     * {@code /}) get the context path prepended, matching {@code @{/...}} URLs.
     */
    public String resolveUrl(String url) {
        if (url.isEmpty() || url.charAt(0) != '/' || url.length() > 1 && url.charAt(1) == '/') {
            return url;
        }
        return contextPath.isEmpty() ? url : contextPath.concat(url);
    }
}
