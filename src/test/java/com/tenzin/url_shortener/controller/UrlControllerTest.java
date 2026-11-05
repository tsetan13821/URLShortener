package com.tenzin.url_shortener.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.tenzin.url_shortener.dto.ShortUrlResponse;
import com.tenzin.url_shortener.exception.ShortUrlNotFoundException;
import com.tenzin.url_shortener.service.UrlShortenerService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web layer only: routing, validation, status codes and error format. The service is mocked. */
@WebMvcTest(UrlController.class)
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlShortenerService service;

    @Test
    void createReturns201WithShortUrl() throws Exception {
        when(service.shorten("https://example.com/page")).thenReturn(new ShortUrlResponse(
                "21", "http://localhost:8080/21", "https://example.com/page",
                Instant.parse("2026-11-05T10:15:30Z")));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "  https://example.com/page  "}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost:8080/21"))
                .andExpect(jsonPath("$.shortCode").value("21"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/21"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/page"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-05T10:15:30Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"url\": \"\"}",
            "{\"url\": \"   \"}",
            "{}",
            "{\"url\": \"not a url\"}",
            "{\"url\": \"example.com\"}",
            "{\"url\": \"ftp://example.com/file\"}",
            "{\"url\": \"javascript:alert(1)\"}",
            "{\"url\": \"https://\"}",
            "{\"url\": \"https://hjfktgdkyfluytliu\"}",
            "{\"url\": \"https://server.dash\"}",
            "{\"url\": \"http://localhost:8080/1\"}"
    })
    void createRejectsInvalidUrlsWith400(String body) throws Exception {
        mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("url"));

        verify(service, never()).shorten(anyString());
    }

    @Test
    void createRejectsTooLongUrl() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(2048);

        mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"" + longUrl + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("2048")));
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createRejectsNonJsonContentType() throws Exception {
        mockMvc.perform(post("/api/urls").contentType(MediaType.TEXT_PLAIN).content("https://example.com"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void redirectReturns302ToOriginalUrl() throws Exception {
        when(service.resolve("21")).thenReturn("https://example.com/page?q=1");

        mockMvc.perform(get("/21"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page?q=1"));
    }

    @Test
    void redirectUnknownCodeReturns404ProblemDetail() throws Exception {
        when(service.resolve("zzz")).thenThrow(new ShortUrlNotFoundException("zzz"));

        mockMvc.perform(get("/zzz"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Short URL not found"))
                .andExpect(jsonPath("$.shortCode").value("zzz"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/favicon.ico", "/abc-def", "/aaaaaaaaaaaa" /* 12 chars */})
    void pathsThatCannotBeShortCodesReturn404WithoutTouchingTheService(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verify(service, never()).resolve(anyString());
    }

    // ---- Browser-friendly behaviour ------------------------------------------------

    /** What Chrome/Firefox/Edge send when you open a link. */
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @Test
    void trailingSlashRedirectsLikeTheBareCode() throws Exception {
        when(service.resolve("21")).thenReturn("https://example.com/page");

        mockMvc.perform(get("/21/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/page"));
    }

    @Test
    void browserOpeningUnknownCodeGetsHtmlNotFoundPage() throws Exception {
        when(service.resolve("zzz")).thenThrow(new ShortUrlNotFoundException("zzz"));

        mockMvc.perform(get("/zzz").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("This link doesn't go anywhere")));
    }

    @Test
    void browserOpeningImpossiblePathGetsHtmlNotFoundPage() throws Exception {
        mockMvc.perform(get("/not-a-code").header("Accept", BROWSER_ACCEPT))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/problem+json"})
    void apiClientsStillGetJsonForUnknownCodes(String accept) throws Exception {
        when(service.resolve("zzz")).thenThrow(new ShortUrlNotFoundException("zzz"));

        mockMvc.perform(get("/zzz").header("Accept", accept))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.shortCode").value("zzz"));
    }

    @Test
    void validationErrorsIncludeMachineReadableCode() throws Exception {
        mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"ftp://example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"))
                .andExpect(jsonPath("$.errors[0].code").value("HttpUrl"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be a valid http or https URL with a public domain name"));
    }
}
