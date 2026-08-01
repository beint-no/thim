package no.beint.thim;

import java.io.IOException;

public interface TemplateSet {
    boolean supports(Class<?> modelType);

    void render(Object model, RenderContext context, Appendable output) throws IOException;
}
