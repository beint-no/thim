package no.beint.thim.spring;

import no.beint.thim.TemplateSet;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.ServiceLoader;

public final class ThimWebMvcConfigurer implements WebMvcConfigurer {
    private final List<TemplateSet> templates;

    public ThimWebMvcConfigurer() {
        this(ServiceLoader.load(TemplateSet.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    public ThimWebMvcConfigurer(List<TemplateSet> templates) {
        this.templates = List.copyOf(templates);
    }

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new ThimReturnValueHandler(templates));
    }
}
