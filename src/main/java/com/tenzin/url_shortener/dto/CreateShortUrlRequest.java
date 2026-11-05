package com.tenzin.url_shortener.dto;

import com.tenzin.url_shortener.entity.UrlMapping;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/urls}. */
public record CreateShortUrlRequest(
        @NotBlank(message = "url is required")
        @Size(max = UrlMapping.MAX_URL_LENGTH, message = "url must be at most {max} characters")
        @HttpUrl
        String url) {

    public CreateShortUrlRequest {
        // Forgive surrounding whitespace from copy/paste; validation runs on the trimmed value.
        if (url != null) {
            url = url.strip();
        }
    }
}
