package no.beint.thim.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import no.beint.thim.RequestDataValues;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.springframework.web.servlet.resource.ResourceUrlProviderExposingInterceptor;
import org.springframework.web.servlet.support.RequestDataValueProcessor;

import java.util.Map;
import java.util.Objects;

final class SpringRequestDataValues implements RequestDataValues {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final RequestDataValueProcessor processor;
    private final ResourceUrlProvider resourceUrlProvider;

    static RequestDataValues create(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestDataValueProcessor processor
    ) {
        var resourceUrlProvider = resourceUrlProvider(request);
        return new SpringRequestDataValues(request, response, processor, resourceUrlProvider);
    }

    SpringRequestDataValues(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestDataValueProcessor processor
    ) {
        this(request, response, processor, resourceUrlProvider(request));
    }

    private SpringRequestDataValues(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestDataValueProcessor processor,
            ResourceUrlProvider resourceUrlProvider
    ) {
        this.request = Objects.requireNonNull(request);
        this.response = Objects.requireNonNull(response);
        this.processor = processor;
        this.resourceUrlProvider = resourceUrlProvider;
    }

    @Override
    public String processUrl(String url) {
        var processed = processor == null ? url : processor.processUrl(request, url);
        if (processed == null) processed = url;
        if (resourceUrlProvider != null && isApplicationUrl(processed)) {
            var resolved = resourceUrlProvider.getForRequestUrl(request, processed);
            // A root lookup path ("/") can match the first slash in a context-prefixed
            // request URL. Thim application URLs have a known context-path boundary.
            if (resolved == null) resolved = resolveApplicationUrl(processed);
            if (resolved != null) processed = resolved;
        }
        return response.encodeURL(processed);
    }

    private boolean isApplicationUrl(String url) {
        return !url.startsWith("//") && url.startsWith(request.getContextPath() + "/");
    }

    private String resolveApplicationUrl(String url) {
        var contextPath = request.getContextPath();
        var pathEnd = url.length();
        var queryIndex = url.indexOf('?');
        if (queryIndex >= 0) pathEnd = queryIndex;
        var fragmentIndex = url.indexOf('#');
        if (fragmentIndex >= 0) pathEnd = Math.min(pathEnd, fragmentIndex);
        if (!isApplicationUrl(url)) return null;
        var resolved = resourceUrlProvider.getForLookupPath(url.substring(contextPath.length(), pathEnd));
        return resolved == null ? null : contextPath + resolved + url.substring(pathEnd);
    }

    private static ResourceUrlProvider resourceUrlProvider(HttpServletRequest request) {
        var provider = request.getAttribute(ResourceUrlProviderExposingInterceptor.RESOURCE_URL_PROVIDER_ATTR);
        return provider instanceof ResourceUrlProvider resourceUrlProvider ? resourceUrlProvider : null;
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
