package no.beint.thim;

import java.util.Objects;

/**
 * A URL the application vouches for. Values starting with a single {@code /} are
 * application-relative: the render context path is prepended, matching {@code @{/...}}
 * URLs. Do not include the context path in the value. Script-capable schemes, control
 * characters, and blank or leading/trailing whitespace values are rejected.
 */
public record TrustedUrl(String value) {
    public TrustedUrl {
        Objects.requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException("TrustedUrl cannot be blank");
        }
        for (var index = 0; index < value.length(); index++) {
            var ch = value.charAt(index);
            if (ch <= 0x1F || ch == 0x7F) {
                throw new IllegalArgumentException("TrustedUrl cannot contain control characters");
            }
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("TrustedUrl cannot have leading or trailing whitespace");
        }
        if (startsIgnoreCase(value, "javascript:")
                || startsIgnoreCase(value, "vbscript:")
                || startsIgnoreCase(value, "data:text/html")
                || startsIgnoreCase(value, "data:image/svg+xml")
                || startsIgnoreCase(value, "data:application/xhtml+xml")) {
            throw new IllegalArgumentException("TrustedUrl rejects script-capable schemes");
        }
    }

    private static boolean startsIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
