package no.beint.thim;

import java.util.Objects;

/**
 * A URL the application vouches for. Values starting with a single {@code /} are
 * application-relative: the render context path is prepended, matching {@code @{/...}}
 * URLs. Do not include the context path in the value.
 */
public record TrustedUrl(String value) {
    public TrustedUrl {
        Objects.requireNonNull(value);
    }
}
