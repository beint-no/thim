package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.RequestDataValues;
import no.beint.thim.TemplateSet;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.*;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ThimRendererTest {
    @Test
    void rendersUrlFreeTemplateWithoutRequestProcessorAllocation() throws IOException {
        var templates = new RecordingTemplateSet();
        var renderer = new ThimRenderer(List.of(templates));
        var request = new MockHttpServletRequest();
        request.setContextPath("/app");
        request.setAttribute(
                ResourceUrlProviderExposingInterceptor.RESOURCE_URL_PROVIDER_ATTR,
                new ResourceUrlProvider()
        );
        var response = new MockHttpServletResponse();

        renderer.render("model", request, response);

        assertEquals(1, templates.supportsCalls);
        assertSame(RequestDataValues.NONE, templates.context.requestDataValues());
        assertEquals("/app", templates.context.contextPath());
        assertEquals("/asset.js", templates.context.requestDataValues().processUrl("/asset.js"));
        assertEquals("rendered", response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("text/html;charset=UTF-8", response.getContentType());
        assertEquals("rendered".getBytes(StandardCharsets.UTF_8).length, response.getContentLength());
    }

    @Test
    void appliesSpringContentVersionsWithoutAResponseEncodingFilter() throws Exception {
        var resourceUrlProvider = contentVersionResourceUrlProvider();
        var templates = new ResourceUrlTemplateSet("/assets/app.js?theme=dark#module");
        var renderer = new ThimRenderer(List.of(templates));
        var request = new MockHttpServletRequest();
        request.setContextPath("/app");
        request.setRequestURI("/app/");
        var response = new MockHttpServletResponse();

        new ResourceUrlProviderExposingInterceptor(resourceUrlProvider).preHandle(request, response, renderer);
        renderer.render("model", request, response);

        assertEquals(
                "/app/assets/app-bf072e9119077b4e76437a93986787ef.js?theme=dark#module",
                response.getContentAsString()
        );
    }

    @Test
    void doesNotDoubleVersionUrlsWhenAResponseEncodingFilterIsPresent() throws Exception {
        var resourceUrlProvider = contentVersionResourceUrlProvider();
        var renderer = new ThimRenderer(List.of(new ResourceUrlTemplateSet("/assets/app.js")));
        var request = new MockHttpServletRequest();
        request.setContextPath("/app");
        request.setRequestURI("/app/");
        var response = new MockHttpServletResponse();

        new ResourceUrlEncodingFilter().doFilter(request, response, (wrappedRequest, wrappedResponse) -> {
            wrappedRequest.setAttribute(
                    ResourceUrlProviderExposingInterceptor.RESOURCE_URL_PROVIDER_ATTR,
                    resourceUrlProvider
            );
            renderer.render(
                    "model",
                    (HttpServletRequest) wrappedRequest,
                    (HttpServletResponse) wrappedResponse
            );
        });

        assertEquals("/app/assets/app-bf072e9119077b4e76437a93986787ef.js", response.getContentAsString());
    }

    @Test
    void leavesUnresolvedAndExternalUrlsUnchanged() throws Exception {
        var request = new MockHttpServletRequest();
        request.setContextPath("/app");
        request.setRequestURI("/app/");
        request.setAttribute(
                ResourceUrlProviderExposingInterceptor.RESOURCE_URL_PROVIDER_ATTR,
                contentVersionResourceUrlProvider()
        );
        var values = SpringRequestDataValues.create(request, new MockHttpServletResponse(), null);

        assertEquals("/app/assets/missing.js", values.processUrl("/app/assets/missing.js"));
        assertEquals("https://cdn.example/app.js", values.processUrl("https://cdn.example/app.js"));
        assertEquals("//cdn.example/app.js", values.processUrl("//cdn.example/app.js"));
    }

    @Test
    void responseEncodesTheUrlAfterRequestDataValueProcessing() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse() {
            @Override
            public String encodeURL(@NonNull String url) {
                return "encoded:" + url;
            }
        };
        var values = new SpringRequestDataValues(request, response, new PrefixingRequestDataValueProcessor());

        assertEquals("encoded:processed:/asset.js", values.processUrl("/asset.js"));
    }

    private static final class RecordingTemplateSet implements TemplateSet {
        private int supportsCalls;
        private RenderContext context;

        @Override
        public boolean supports(Class<?> modelType) {
            supportsCalls++;
            return modelType == String.class;
        }

        @Override
        public boolean supportsReturnType(Class<?> returnType) {
            return supports(returnType);
        }

        @Override
        public boolean usesRequestDataValues(Class<?> modelType) {
            return false;
        }

        @Override
        public void render(Object model, RenderContext context, HtmlOutput output) throws IOException {
            this.context = context;
            var bytes = "rendered".getBytes(StandardCharsets.UTF_8);
            output.raw(bytes, 0, bytes.length);
        }
    }

    private static ResourceUrlProvider contentVersionResourceUrlProvider() throws Exception {
        var resourceHandler = new ResourceHttpRequestHandler();
        resourceHandler.setLocations(List.of(new ClassPathResource("static/")));
        resourceHandler.setResourceResolvers(List.of(
                new VersionResourceResolver().addContentVersionStrategy("/**"),
                new PathResourceResolver()
        ));
        resourceHandler.afterPropertiesSet();
        var resourceUrlProvider = new ResourceUrlProvider();
        resourceUrlProvider.setHandlerMap(Map.of("/assets/**", resourceHandler));
        return resourceUrlProvider;
    }

    private record ResourceUrlTemplateSet(String url) implements TemplateSet {

        @Override
            public boolean supports(Class<?> modelType) {
                return modelType == String.class;
            }

            @Override
            public boolean supportsReturnType(Class<?> returnType) {
                return supports(returnType);
            }

            @Override
            public void render(Object model, RenderContext context, HtmlOutput output) throws IOException {
                output.text(context.requestDataValues().processUrl(context.contextPath() + url));
            }
        }

    private static final class PrefixingRequestDataValueProcessor implements RequestDataValueProcessor {
        @Override
        public String processAction(@NonNull HttpServletRequest request, @NonNull String action, String method) {
            return action;
        }

        @Override
        public String processFormFieldValue(@NonNull HttpServletRequest request, String name, String value, String type) {
            return value;
        }

        @Override
        public Map<String, String> getExtraHiddenFields(@NonNull HttpServletRequest request) {
            return Map.of();
        }

        @Override
        public String processUrl(@NonNull HttpServletRequest request, @NonNull String url) {
            return "processed:" + url;
        }
    }
}
