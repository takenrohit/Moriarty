package dev.investigator.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.util.Locale;

/** Validates {@link HttpUrl}: {@code URI.create(s)} is absolute, has a host, and uses http or https. */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return isHttpUrl(value);
    }

    /** Returns whether {@code value} is an absolute {@code http} or {@code https} URL with a host. */
    public static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isEmpty()) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("http") || scheme.equals("https");
    }
}
