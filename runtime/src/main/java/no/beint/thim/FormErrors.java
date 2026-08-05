package no.beint.thim;

import java.util.ArrayList;
import java.util.List;

/**
 * Form binding errors carried on a page model as a property named {@code errors}.
 * Generated renderers use it for {@code th:errors} content and to redisplay the
 * submitted raw value in {@code th:field} controls after a failed post. Render
 * with {@link #NONE} when the form has no errors.
 */
public final class FormErrors {
    public static final FormErrors NONE = new FormErrors(List.of());

    private final List<FieldError> errors;

    public FormErrors(List<FieldError> errors) {
        this.errors = List.copyOf(errors);
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    public boolean hasErrors(String field) {
        return errors.stream().anyMatch(error -> error.field().equals(field));
    }

    public List<FieldError> all() {
        return errors;
    }

    public List<String> messages(String field) {
        var messages = new ArrayList<String>();
        for (var error : errors) {
            if (error.field().equals(field)) {
                messages.add(error.message());
            }
        }
        return messages;
    }

    /**
     * The raw submitted value to redisplay for the field, or {@code fallback}
     * (the current model value) when nothing was rejected.
     */
    public String value(String field, String fallback) {
        for (var error : errors) {
            if (error.field().equals(field) && error.rejectedValue() != null) {
                return error.rejectedValue();
            }
        }
        return fallback;
    }
}
