package no.beint.thim.spring;

import no.beint.thim.TemplateSet;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

public final class ThimWebMvcConfigurer implements WebMvcConfigurer {
    private final TemplateSet templates;

    public ThimWebMvcConfigurer(TemplateSet templates) {
        this.templates = templates;
    }

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new ThimReturnValueHandler(templates));
    }
}
