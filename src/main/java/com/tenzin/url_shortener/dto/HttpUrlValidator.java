package com.tenzin.url_shortener.dto;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Accepts only absolute http(s) URLs with a host. This blocks schemes that are dangerous to
 * redirect to ({@code javascript:}, {@code data:}, {@code file:}), relative paths, and strings
 * that aren't RFC 3986 URIs (e.g. containing raw spaces).
 */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null
                    && ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                    && host != null
                    && !host.isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
