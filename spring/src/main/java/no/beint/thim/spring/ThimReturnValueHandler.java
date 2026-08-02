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
import java.util.List;
import java.util.Objects;

public final class ThimReturnValueHandler implements HandlerMethodReturnValueHandler {
    private final List<TemplateSet> templates;

    public ThimReturnValueHandler(List<TemplateSet> templates) {
        this.templates = List.copyOf(templates);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return templates.stream().anyMatch(templateSet -> templateSet.supports(returnType.getParameterType()));
    }

    @Override
    public void handleReturnValue(
            Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer modelAndViewContainer,
            NativeWebRequest webRequest
    ) throws Exception {
        var templateSet = templates.stream()
                .filter(candidate -> candidate.supports(returnType.getParameterType()))
                .findFirst()
                .orElseThrow();
        var request = Objects.requireNonNull(webRequest.getNativeRequest(HttpServletRequest.class));
        var response = Objects.requireNonNull(webRequest.getNativeResponse(HttpServletResponse.class));
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html");
        modelAndViewContainer.setRequestHandled(true);
        var output = new HtmlOutput(response.getOutputStream());
        templateSet.render(returnValue, new RenderContext(request.getLocale(), request.getContextPath()), output);
        output.flush();
    }
}
