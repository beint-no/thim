package no.beint.thim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {
    @Test
    void rendersToStringAndOutputStreamWithoutWebFramework() throws IOException {
        var renderer = TemplateRenderer.of(new GreetingTemplateSet());

        assertTrue(renderer.supports(GreetingModel.class));
        assertTrue(renderer.supportsReturnType(GreetingModel.class));
        assertFalse(renderer.supports(String.class));

        assertEquals(
                "en::Hello Ada &amp; Bob",
                renderer.renderToString(new GreetingModel("Ada & Bob"), Locale.ENGLISH)
        );
        assertEquals(
                "nb:/app:Hello Ada",
                renderer.renderToString(new GreetingModel("Ada"), Locale.forLanguageTag("nb"), "/app")
        );

        var bytes = new ByteArrayOutputStream();
        renderer.render(new GreetingModel("Stream"), Locale.ENGLISH, "/ctx", bytes);
        assertEquals("en:/ctx:Hello Stream", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnknownModels() {
        var renderer = TemplateRenderer.of(new GreetingTemplateSet());

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> renderer.renderToString("unknown", Locale.ENGLISH)
        );
        assertEquals("No compiled template for java.lang.String", failure.getMessage());
    }

    @Test
    void loadsTemplateSetsFromServiceLoader(@TempDir Path directory) throws IOException {
        var serviceFile = directory.resolve("META-INF/services/no.beint.thim.TemplateSet");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, GreetingTemplateSet.class.getName() + "\n", StandardCharsets.UTF_8);

        try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            var renderer = TemplateRenderer.fromServiceLoader(loader);
            assertTrue(renderer.supports(GreetingModel.class));
            assertEquals("en::Hello Loader", renderer.renderToString(new GreetingModel("Loader"), Locale.ENGLISH));
        }
    }

    public record GreetingModel(String name) {}

    public static final class GreetingTemplateSet implements TemplateSet {
        @Override
        public boolean supports(Class<?> modelType) {
            return modelType == GreetingModel.class;
        }

        @Override
        public void render(Object model, RenderContext context, HtmlOutput output) throws IOException {
            var greeting = (GreetingModel) model;
            output.text(context.locale().toLanguageTag());
            output.text(":");
            output.text(context.contextPath());
            output.text(":Hello ");
            output.text(greeting.name());
        }
    }
}
