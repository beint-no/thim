package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import no.beint.thim.RequestDataValues;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.Map;
import java.util.Objects;

final class SpringRequestDataValues implements RequestDataValues {
    private final HttpServletRequest request;
    private final RequestDataValueProcessor processor;

    SpringRequestDataValues(HttpServletRequest request, RequestDataValueProcessor processor) {
        this.request = Objects.requireNonNull(request);
        this.processor = processor;
    }

    @Override
    public String processUrl(String url) {
        if (processor == null) return url;
        var processed = processor.processUrl(request, url);
        return processed == null ? url : processed;
    }

    @Override
    public String processAction(String action, String method) {
        if (processor == null) return action;
        var processed = processor.processAction(request, action, method);
        return processed == null ? action : processed;
    }

    @Override
    public String processFormFieldValue(String name, String value, String type) {
        if (processor == null) return value;
        var processed = processor.processFormFieldValue(request, name, value, type);
        return processed == null ? value : processed;
    }

    @Override
    public Map<String, String> extraHiddenFields() {
        if (processor == null) return Map.of();
        var fields = processor.getExtraHiddenFields(request);
        return fields == null ? Map.of() : fields;
    }
}
