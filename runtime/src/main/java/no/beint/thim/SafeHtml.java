package no.beint.thim;

import java.util.Objects;

public record SafeHtml(String value) {
    public SafeHtml {
        Objects.requireNonNull(value);
    }
}
