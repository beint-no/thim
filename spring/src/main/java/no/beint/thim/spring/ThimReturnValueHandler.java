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
        var type = returnType.getParameterType();
        return ThimResult.class.isAssignableFrom(type) || renderer.supportsReturnType(type);
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
        var response = Objects.requireNonNull(webRequest.getNativeResponse(HttpServletResponse.class));
        modelAndViewContainer.setRequestHandled(true);
        if (returnValue instanceof ThimResult.Redirect redirect) {
            response.sendRedirect(redirect.path());
            return;
        }
        var model = returnValue instanceof ThimResult.Page page ? page.model() : returnValue;
        var request = Objects.requireNonNull(webRequest.getNativeRequest(HttpServletRequest.class));
        renderer.render(model, request, response);
    }
}
