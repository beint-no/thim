package no.beint.thim;

import java.util.Locale;
import java.util.Objects;

/** Integer cardinal categories derived from Unicode CLDR 49 plural rules. */
public final class PluralRules {
    private PluralRules() {}

    public static String cardinal(Locale locale, long value) {
        Objects.requireNonNull(locale);
        return cardinal(locale.getLanguage(), locale.getCountry(), value);
    }

    /** Used by generated renderers, which have already resolved the effective catalog locale. */
    public static String cardinal(String language, String country, long value) {
        Objects.requireNonNull(language);
        Objects.requireNonNull(country);
        if (value < 0 && value != Long.MIN_VALUE) value = -value;
        var mod10 = Math.abs(value % 10);
        var mod100 = Math.abs(value % 100);
        return switch (language) {
            case "fr" -> {
                if (value == 0 || value == 1) yield "one";
                if (value != 0 && value % 1_000_000 == 0) yield "many";
                yield "other";
            }
            case "pt" -> {
                var portugal = country.equals("PT");
                if (portugal ? value == 1 : value == 0 || value == 1) yield "one";
                if (value != 0 && value % 1_000_000 == 0) yield "many";
                yield "other";
            }
            case "es", "ca", "gl", "it" -> {
                if (value == 1) yield "one";
                if (value != 0 && value % 1_000_000 == 0) yield "many";
                yield "other";
            }
            case "is" -> mod10 == 1 && mod100 != 11 ? "one" : "other";
            case "cs", "sk" -> {
                if (value == 1) yield "one";
                if (value >= 2 && value <= 4) yield "few";
                yield "other";
            }
            case "pl" -> {
                if (value == 1) yield "one";
                if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) yield "few";
                if (value != 1 && (mod10 <= 1 || mod10 >= 5 || mod100 >= 12 && mod100 <= 14)) yield "many";
                yield "other";
            }
            case "ro" -> {
                if (value == 1) yield "one";
                if (value == 0 || value != 1 && mod100 >= 1 && mod100 <= 19) yield "few";
                yield "other";
            }
            case "sl" -> switch ((int) mod100) {
                case 1 -> "one";
                case 2 -> "two";
                case 3, 4 -> "few";
                default -> "other";
            };
            case "bs", "hr", "sr" -> {
                if (mod10 == 1 && mod100 != 11) yield "one";
                if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) yield "few";
                yield "other";
            }
            case "lv" -> {
                if (mod10 == 0 || mod100 >= 11 && mod100 <= 19) yield "zero";
                if (mod10 == 1 && mod100 != 11) yield "one";
                yield "other";
            }
            case "lt" -> {
                if (mod10 == 1 && !(mod100 >= 11 && mod100 <= 19)) yield "one";
                if (mod10 >= 2 && mod10 <= 9 && !(mod100 >= 11 && mod100 <= 19)) yield "few";
                yield "other";
            }
            case "ga" -> {
                if (value == 1) yield "one";
                if (value == 2) yield "two";
                if (value >= 3 && value <= 6) yield "few";
                if (value >= 7 && value <= 10) yield "many";
                yield "other";
            }
            case "cy" -> {
                if (value == 0) yield "zero";
                if (value == 1) yield "one";
                if (value == 2) yield "two";
                if (value == 3) yield "few";
                if (value == 6) yield "many";
                yield "other";
            }
            case "gd" -> {
                if (value == 1 || value == 11) yield "one";
                if (value == 2 || value == 12) yield "two";
                if (value >= 3 && value <= 10 || value >= 13 && value <= 19) yield "few";
                yield "other";
            }
            case "af", "bg", "da", "de", "el", "en", "eo", "et", "eu", "fi", "fo",
                    "hu", "nb", "nl", "nn", "no", "sq", "sv", "sw" -> value == 1 ? "one" : "other";
            default -> throw new IllegalArgumentException(
                    "Unsupported plural locale " + language + (country.isEmpty() ? "" : "-" + country));
        };
    }
}
