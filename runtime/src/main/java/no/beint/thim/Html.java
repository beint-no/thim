package no.beint.thim;

import java.io.IOException;

public final class Html {
    private Html() {
    }

    public static void text(Appendable output, Object value) throws IOException {
        if (value == null) {
            return;
        }
        text(output, value.toString());
    }

    public static void text(Appendable output, String text) throws IOException {
        if (text == null) {
            return;
        }
        var start = 0;
        for (var index = 0; index < text.length(); index++) {
            var escaped = switch (text.charAt(index)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (escaped == null) {
                continue;
            }
            output.append(text, start, index).append(escaped);
            start = index + 1;
        }
        output.append(text, start, text.length());
    }
}
