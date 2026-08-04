package no.beint.thim;

import java.util.Map;

public interface RequestDataValues {
    RequestDataValues NONE = new RequestDataValues() {};

    default String processUrl(String url) {
        return url;
    }

    default String processAction(String action, String method) {
        return action;
    }

    default String processFormFieldValue(String name, String value, String type) {
        return value;
    }

    default Map<String, String> extraHiddenFields() {
        return Map.of();
    }
}
