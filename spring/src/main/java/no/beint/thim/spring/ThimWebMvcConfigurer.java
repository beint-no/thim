package no.beint.thim.spring;

import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

public final class ThimWebMvcConfigurer implements WebMvcConfigurer {
    private final ThimRenderer renderer;

    public ThimWebMvcConfigurer(ThimRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        handlers.add(new ThimReturnValueHandler(renderer));
    }
}
