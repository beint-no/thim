package no.beint.thim;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RenderContextTest {
    @Test
    void prependsANonEmptyContextPathToApplicationRelativeUrls() {
        var context = new RenderContext(Locale.ENGLISH, "/app");
        assertEquals("/app/orders/1", context.resolveUrl("/orders/1"));
        assertEquals("/app/", context.resolveUrl("/"));
    }

    @Test
    void returnsTheSameInstanceWhenTheContextPathIsEmpty() {
        var context = new RenderContext(Locale.ENGLISH, "");
        var url = "/orders/1";
        assertSame(url, context.resolveUrl(url));
    }

    @Test
    void leavesProtocolRelativeAndAbsoluteUrlsUnchanged() {
        var context = new RenderContext(Locale.ENGLISH, "/app");
        assertEquals("//cdn.example/a", context.resolveUrl("//cdn.example/a"));
        assertEquals("https://example.com", context.resolveUrl("https://example.com"));
        assertEquals("#top", context.resolveUrl("#top"));
    }
}
