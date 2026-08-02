package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.TemplateSet;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ThimReturnValueHandler implements HandlerMethodReturnValueHandler {
    private final TemplateSet templates;

    public ThimReturnValueHandler(TemplateSet templates) {
        this.templates = Objects.requireNonNull(templates);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return templates.supports(returnType.getParameterType());
    }

    @Override
    public void handleReturnValue(
            Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer modelAndViewContainer,
            NativeWebRequest webRequest
    ) throws Exception {
        var request = Objects.requireNonNull(webRequest.getNativeRequest(HttpServletRequest.class));
        var response = Objects.requireNonNull(webRequest.getNativeResponse(HttpServletResponse.class));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html");
        modelAndViewContainer.setRequestHandled(true);
        var output = new HtmlOutput(response.getOutputStream());
        templates.render(returnValue, new RenderContext(request.getLocale(), request.getContextPath()), output);
        output.flush();
    }
}
