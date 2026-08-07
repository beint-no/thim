package no.beint.thim.spring;

import java.util.Objects;

/** A controller result that explicitly chooses between rendering a page model and redirecting. */
public sealed interface ThimResult permits ThimResult.Page, ThimResult.Redirect {
    record Page(Object model) implements ThimResult {
        public Page {
            Objects.requireNonNull(model, "model");
        }
    }

    record Redirect(String path) implements ThimResult {
        public Redirect {
            Objects.requireNonNull(path, "path");
        }
    }
}
