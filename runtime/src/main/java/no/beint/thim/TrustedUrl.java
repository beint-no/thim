package no.beint.thim;

import java.util.Objects;

/**
 * A URL the application vouches for. Values starting with a single {@code /} are
 * application-relative: the render context path is prepended, matching {@code @{/...}}
 * URLs. Do not include the context path in the value. Script-capable schemes and
 * blank or newline-containing values are rejected.
 */
public record TrustedUrl(String value) {
    public TrustedUrl {
        Objects.requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException("TrustedUrl cannot be blank");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("TrustedUrl cannot contain a line break");
        }
        var trimmed = value.strip();
        if (startsIgnoreCase(trimmed, "javascript:")
                || startsIgnoreCase(trimmed, "vbscript:")
                || startsIgnoreCase(trimmed, "data:text/html")) {
            throw new IllegalArgumentException("TrustedUrl rejects script-capable schemes");
        }
    }

    private static boolean startsIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
