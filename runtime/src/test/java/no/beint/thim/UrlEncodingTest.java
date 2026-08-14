package no.beint.thim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UrlEncodingTest {
    @Test
    void leavesUnreservedValuesUnchanged() {
        assertEquals("item-1", UrlEncoding.pathSegment("item-1"));
        assertEquals("A.b_c~", UrlEncoding.query("A.b_c~"));
    }

    @Test
    void percentEncodesBytesNotFormEncoding() {
        assertEquals("a%20b", UrlEncoding.pathSegment("a b"));
        assertEquals("a%2Fb", UrlEncoding.pathSegment("a/b"));
        assertEquals("%C3%A6%C3%B8%C3%A5", UrlEncoding.pathSegment("æøå"));
        assertEquals("%E2%9C%93", UrlEncoding.pathSegment("✓"));
        assertEquals("%F0%9F%98%80", UrlEncoding.pathSegment("😀"));
        assertEquals("%EF%BF%BD", UrlEncoding.pathSegment("\uD800"));
    }

    @Test
    void appendsOptionalQueryParametersWithoutLeavingATraceWhenNull() {
        var url = new StringBuilder("/inbox");
        UrlEncoding.appendQuery(url, "q", null);
        assertEquals("/inbox", url.toString());
        UrlEncoding.appendQuery(url, "q", "a b");
        UrlEncoding.appendQuery(url, "tag", java.util.List.of("one", "two"));
        assertEquals("/inbox?q=a%20b&tag=one&tag=two", url.toString());
    }
}
