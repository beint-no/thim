package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import java.util.Objects;

public final class ThimReturnValueHandler implements HandlerMethodReturnValueHandler {
    private final ThimRenderer renderer;

    public ThimReturnValueHandler(ThimRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return renderer.supports(returnType.getParameterType());
    }

    @Override
    public void handleReturnValue(
            Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer modelAndViewContainer,
            NativeWebRequest webRequest
    ) throws Exception {
        if (returnValue == null) {
            modelAndViewContainer.setRequestHandled(true);
            return;
        }
        var request = Objects.requireNonNull(webRequest.getNativeRequest(HttpServletRequest.class));
        var response = Objects.requireNonNull(webRequest.getNativeResponse(HttpServletResponse.class));
        modelAndViewContainer.setRequestHandled(true);
        renderer.render(returnValue, request, response);
    }
}
