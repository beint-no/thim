package no.beint.thim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedUrlTest {
    @Test
    void acceptsApplicationRelativeAndHttpsUrls() {
        assertEquals("/orders/1", new TrustedUrl("/orders/1").value());
        assertEquals("https://example.com", new TrustedUrl("https://example.com").value());
        assertEquals("#", new TrustedUrl("#").value());
    }

    @Test
    void rejectsBlankScriptAndMultilineValues() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl(""));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("   "));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl(" JavaScript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("vbscript:msg"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("data:text/html,<h1>x</h1>"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("/a\n/b"));
    }
}
