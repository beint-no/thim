package no.beint.thim;

import java.util.Locale;

/**
 * A type-safe message and its validated arguments, compiled from a Thim locale catalog.
 * Resolving produces plain text; callers choose the appropriate output-context encoding.
 */
@FunctionalInterface
public interface CompiledMessage {
    String resolve(Locale locale);
}
