package no.beint.thim;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluralRulesTest {
    @Test
    void selectsEnglishAndNorwegianCardinals() {
        assertEquals("one", PluralRules.cardinal(Locale.ENGLISH, 1));
        assertEquals("one", PluralRules.cardinal(Locale.ENGLISH, -1));
        assertEquals("other", PluralRules.cardinal(Locale.ENGLISH, 0));
        assertEquals("other", PluralRules.cardinal(Locale.forLanguageTag("nb"), 2));
    }

    @Test
    void selectsPolishCardinals() {
        var locale = Locale.forLanguageTag("pl");
        assertEquals("one", PluralRules.cardinal(locale, 1));
        assertEquals("few", PluralRules.cardinal(locale, 2));
        assertEquals("many", PluralRules.cardinal(locale, 5));
        assertEquals("many", PluralRules.cardinal(locale, 12));
        assertEquals("few", PluralRules.cardinal(locale, 22));
    }

    @Test
    void selectsLessCommonCardinalCategories() {
        assertEquals("many", PluralRules.cardinal(Locale.FRENCH, 1_000_000));
        assertEquals("many", PluralRules.cardinal(Locale.forLanguageTag("gl"), 1_000_000));
        assertEquals("other", PluralRules.cardinal(Locale.forLanguageTag("pt-PT"), 0));
        assertEquals("other", PluralRules.cardinal("pt", "PT", 0));
        assertEquals("few", PluralRules.cardinal(Locale.forLanguageTag("ro"), 101));
        assertEquals("two", PluralRules.cardinal(Locale.forLanguageTag("sl"), 102));
        assertEquals("zero", PluralRules.cardinal(Locale.forLanguageTag("lv"), 10));
    }

    @Test
    void rejectsLocalesWithoutEmbeddedRules() {
        assertThrows(IllegalArgumentException.class, () -> PluralRules.cardinal(Locale.JAPANESE, 1));
    }
}
