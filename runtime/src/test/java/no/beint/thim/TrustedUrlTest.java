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

    @Test
    void rejectsControlCharactersDangerousDataUrlsAndSurroundingWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("java\tscript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("\u0001javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("/a\r/b"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("/a\u0000/b"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("/a\u007F/b"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("data:image/svg+xml,<svg/>"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("DATA:IMAGE/SVG+XML;base64,PHN2Zy8+"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("data:application/xhtml+xml,<html/>"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("DATA:Application/XHTML+XML,<html/>"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl(" /orders/1"));
        assertThrows(IllegalArgumentException.class, () -> new TrustedUrl("/orders/1 "));
    }
}
