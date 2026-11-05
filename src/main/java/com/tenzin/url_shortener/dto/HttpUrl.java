package com.tenzin.url_shortener.dto;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The annotated string must be an absolute {@code http} or {@code https} URL whose host is a
 * real public domain (known top-level domain) or a public IP address. See {@link HttpUrlValidator}.
 * {@code null} is considered valid; combine with {@code @NotBlank} to require a value.
 */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "must be a valid http or https URL with a public domain name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
