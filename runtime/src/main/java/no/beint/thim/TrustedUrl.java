package no.beint.thim;

import java.util.Objects;

public record TrustedUrl(String value) {
    public TrustedUrl {
        Objects.requireNonNull(value);
    }
}
