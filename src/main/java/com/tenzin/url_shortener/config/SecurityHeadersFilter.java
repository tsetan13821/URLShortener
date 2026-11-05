package com.tenzin.url_shortener.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser security headers on every response (pages, API, redirects, errors).
 *
 * <ul>
 *   <li><b>Content-Security-Policy:</b> the page may only load its own scripts, styles and images
 *       (no inline code, nothing from other sites), call only its own API, and can't be framed.
 *       So even if a bug ever let a link's text into the page as HTML, it couldn't run.</li>
 *   <li><b>X-Content-Type-Options: nosniff:</b> browsers use the declared type, never guess one.</li>
 *   <li><b>Referrer-Policy:</b> sites a short link leads to see this site's origin at most, never
 *       the full page URL.</li>
 *   <li><b>Strict-Transport-Security:</b> only on HTTPS requests (incl. HTTPS ended at a trusted
 *       proxy, see {@code server.forward-headers-strategy}): browsers then refuse plain HTTP to this
 *       host for a year. Never sent over plain HTTP, so local development on http://localhost is
 *       unaffected.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY"); // frame-ancestors for older browsers
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
        chain.doFilter(request, response);
    }
}
