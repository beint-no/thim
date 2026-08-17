package no.beint.thim;

import java.io.IOException;

public interface TemplateSet {
    boolean supports(Class<?> modelType);

    default boolean supportsReturnType(Class<?> returnType) {
        return supports(returnType);
    }

    /**
     * Whether rendering the given model can consult request-scoped form or URL processing.
     * Existing and custom template sets default to {@code true} for compatibility.
     */
    default boolean usesRequestDataValues(Class<?> modelType) {
        return true;
    }

    void render(Object model, RenderContext context, HtmlOutput output) throws IOException;
}
