package no.beint.thim.spring;

import java.util.Objects;

/** Metadata for one synchronous render attempt. */
public final class ThimRender {
    public enum Mode {
        SERVLET_RESPONSE,
        STRING
    }

    private final String templateId;
    private final Class<?> modelType;
    private final Mode mode;
    private final String requestUri;

    ThimRender(Class<?> modelType, Mode mode, String requestUri) {
        this.modelType = Objects.requireNonNull(modelType, "modelType");
        this.templateId = modelType.getName();
        this.mode = Objects.requireNonNull(mode, "mode");
        this.requestUri = Objects.requireNonNull(requestUri, "requestUri");
    }

    /**
     * Stable identifier for the compiled template, defined as the fully qualified runtime
     * page-model class name.
     */
    public String templateId() {
        return templateId;
    }

    public Class<?> modelType() {
        return modelType;
    }

    public Mode mode() {
        return mode;
    }

    /** The servlet request URI, or an empty string for string rendering. */
    public String requestUri() {
        return requestUri;
    }
}
