package com.tenzin.url_shortener.controller;

import com.tenzin.url_shortener.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.validator.routines.InetAddressValidator;
import org.springframework.stereotype.Component;

/**
 * Works out which IP address a request came from, for rate limiting.
 *
 * <p>By default: the TCP connection's address ({@link HttpServletRequest#getRemoteAddr()}), which is
 * the client itself when it connects directly, or the client from {@code X-Forwarded-For} when
 * {@code server.forward-headers-strategy} trusts a proxy in front.
 *
 * <p>That isn't enough behind some platforms. On Render (behind Cloudflare), {@code X-Forwarded-For}
 * keeps whatever the client sent and then adds a Cloudflare edge address, which changes from request
 * to request: every request looked like a new client and the limit never triggered. Render instead
 * sets {@code True-Client-IP} itself, overwriting any value the client sent. With
 * {@code app.rate-limit.client-ip-header} set to such a header, its value is used.
 *
 * <p>The header value must be a plain IP address; anything else (missing, a list, a hostname) falls
 * back to the connection's address, so a malformed header can't be used to invent new identities.
 */
@Component
public class ClientIpResolver {

    private final String header;

    public ClientIpResolver(AppProperties properties) {
        String configured = properties.rateLimit().clientIpHeader();
        this.header = configured == null || configured.isBlank() ? null : configured.strip();
    }

    public String resolve(HttpServletRequest request) {
        if (header != null) {
            String value = request.getHeader(header);
            if (value != null && InetAddressValidator.getInstance().isValid(value.strip())) {
                return value.strip();
            }
        }
        return request.getRemoteAddr();
    }
}
