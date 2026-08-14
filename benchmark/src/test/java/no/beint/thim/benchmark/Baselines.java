package no.beint.thim.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

final class Baselines {
    private static final Properties VALUES = load();

    private Baselines() {}

    static long ceiling(String name) {
        var raw = VALUES.getProperty(name);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Missing render baseline '" + name + "'");
        }
        return Long.parseLong(raw.trim());
    }

    private static Properties load() {
        var values = new Properties();
        try (var input = Baselines.class.getResourceAsStream("/render-baselines.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing render-baselines.properties");
            }
            values.load(input);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return values;
    }
}
