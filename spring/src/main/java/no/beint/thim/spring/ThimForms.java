package no.beint.thim.spring;

import no.beint.thim.FieldError;
import no.beint.thim.FormErrors;
import org.springframework.validation.BindingResult;

import java.util.ArrayList;

/**
 * Converts Spring binding results into Thim's dependency-free {@link FormErrors}
 * so page models can carry them without referencing Spring types.
 */
public final class ThimForms {
    private ThimForms() {}

    public static FormErrors errors(BindingResult binding) {
        var errors = new ArrayList<FieldError>();
        for (var error : binding.getFieldErrors()) {
            var rejected = error.getRejectedValue();
            var message = error.getDefaultMessage();
            errors.add(new FieldError(
                    error.getField(),
                    message == null ? "invalid value" : message,
                    rejected == null ? null : rejected.toString()
            ));
        }
        return errors.isEmpty() ? FormErrors.NONE : new FormErrors(errors);
    }
}
