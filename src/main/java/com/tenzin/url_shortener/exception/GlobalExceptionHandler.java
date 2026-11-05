package com.tenzin.url_shortener.exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single place that maps exceptions to HTTP responses.
 *
 * <p>API clients get RFC 9457 "problem details" ({@code application/problem+json}). People who
 * open a dead link in a browser (requests that explicitly accept {@code text/html}) get a
 * readable HTML "not found" page instead, with the same 404 status.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own exceptions
 * (malformed JSON, wrong media type, unsupported method, no matching route, ...) also come out
 * as consistent problem details instead of the default error page.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    /** Loaded once; lives outside /static so it isn't also served as a page of its own. */
    private final String notFoundPage = loadPage("pages/not-found.html");

    @ExceptionHandler(ShortUrlNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ShortUrlNotFoundException ex, WebRequest request) {
        if (prefersHtml(request)) {
            return notFoundHtml();
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Short URL not found");
        problem.setProperty("shortCode", ex.getShortCode());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * Too many links created: 429 with {@code Retry-After} (seconds), so well-behaved clients know
     * exactly when to try again. The same value is in the body for clients that only read JSON.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
        long retryAfter = ex.getRetryAfterSeconds();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "You can create up to " + ex.getLimit() + " links per " + describe(ex.getWindow())
                        + ". Try again in " + retryAfter + (retryAfter == 1 ? " second." : " seconds."));
        problem.setTitle("Too many requests");
        problem.setProperty("limit", ex.getLimit());
        problem.setProperty("windowSeconds", ex.getWindow().toSeconds());
        problem.setProperty("retryAfterSeconds", retryAfter);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(problem);
    }

    /** "minute", "10 minutes", "hour", "90 seconds": readable wording for the configured window. */
    static String describe(Duration window) {
        long seconds = window.toSeconds();
        if (seconds % 3600 == 0) {
            return plural(seconds / 3600, "hour");
        }
        if (seconds % 60 == 0) {
            return plural(seconds / 60, "minute");
        }
        return plural(seconds, "second");
    }

    private static String plural(long n, String unit) {
        return n == 1 ? unit : n + " " + unit + "s";
    }

    /** Paths that can't be a short code at all (e.g. /not-a-code): same browser treatment. */
    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (prefersHtml(request)) {
            return notFoundHtml();
        }
        return super.handleNoResourceFoundException(ex, headers, status, request);
    }

    /**
     * Bean Validation failures on {@code @Valid @RequestBody}: list every invalid field with a
     * stable machine-readable {@code code} (the constraint name, e.g. {@code NotBlank},
     * {@code Size}, {@code HttpUrl}) next to the human-readable message.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("field", error.getField());
                    entry.put("code", String.valueOf(error.getCode()));
                    entry.put("message", String.valueOf(error.getDefaultMessage()));
                    return entry;
                })
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Last resort: log the details server-side, never leak them to the client. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal server error");
        return problem;
    }

    /**
     * True only when the client explicitly asks for HTML, as browsers do on navigation
     * ({@code Accept: text/html,...}). A missing header or {@code *}{@code /*} (curl, most HTTP
     * libraries) keeps the JSON response, so API clients are unaffected.
     */
    private static boolean prefersHtml(WebRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return false;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(type -> "text".equals(type.getType()) && "html".equals(type.getSubtype()));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private ResponseEntity<Object> notFoundHtml() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(TEXT_HTML_UTF8).body(notFoundPage);
    }

    private static String loadPage(String classpathLocation) {
        try {
            return new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing error page on classpath: " + classpathLocation, e);
        }
    }
}
