package no.beint.thim;

import java.util.Objects;

/**
 * One binding or validation error for a form field. {@code rejectedValue} is the raw
 * text the user submitted (so it can be redisplayed), or {@code null} when the error
 * is not tied to a submitted value.
 */
public record FieldError(String field, String message, String rejectedValue) {
    public FieldError {
        Objects.requireNonNull(field);
        Objects.requireNonNull(message);
    }
}
