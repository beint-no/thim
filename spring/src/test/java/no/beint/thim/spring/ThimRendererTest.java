package no.beint.thim.spring;

import no.beint.thim.HtmlOutput;
import no.beint.thim.RenderContext;
import no.beint.thim.RequestDataValues;
import no.beint.thim.TemplateSet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ThimRendererTest {
    @Test
    void rendersThroughTheSingleTemplateSetWithoutRequestProcessorAllocation() throws IOException {
        var templates = new RecordingTemplateSet();
        var renderer = new ThimRenderer(List.of(templates));
        var request = new MockHttpServletRequest();
        request.setContextPath("/app");
        var response = new MockHttpServletResponse();

        renderer.render("model", request, response);

        assertEquals(1, templates.supportsCalls);
        assertSame(RequestDataValues.NONE, templates.context.requestDataValues());
        assertEquals("/app", templates.context.contextPath());
        assertEquals("rendered", response.getContentAsString(StandardCharsets.UTF_8));
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
        public void render(Object model, RenderContext context, HtmlOutput output) throws IOException {
            this.context = context;
            var bytes = "rendered".getBytes(StandardCharsets.UTF_8);
            output.raw(bytes, 0, bytes.length);
        }
    }
}
