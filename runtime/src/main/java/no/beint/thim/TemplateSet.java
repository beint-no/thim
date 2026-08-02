package no.beint.thim;

import java.io.IOException;

public interface TemplateSet {
    boolean supports(Class<?> modelType);

    default boolean supportsReturnType(Class<?> returnType) {
        return supports(returnType);
    }

    void render(Object model, RenderContext context, HtmlOutput output) throws IOException;
}
