package com.tenzin.url_shortener.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpUrlValidatorTest {

    private final HttpUrlValidator validator = new HttpUrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "https://github.com/tsetan13821/URLShortener",
            "https://leetcode.com/u/tenzintsetan/",
            "https://shop.example.co.in/cart",
            "https://my-site.dev",
            "https://EXAMPLE.COM/UPPER",
            "HTTPS://example.org",
            "https://example.com:8443/admin",
            "https://user@example.com/",
            "https://example.com./trailing-dot",
            "https://xn--mnchen-3ya.de",            // münchen.de in punycode
            "https://8.8.8.8/dns",                  // public IPv4
            "http://[2606:4700:4700::1111]/",        // public IPv6
    })
    void acceptsRealPublicUrls(String url) {
        assertThat(validator.isValid(url, null)).as(url).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Not a real domain
            "https://hjfktgdkyfluytliu",             // bare word (the reported case)
            "https://server.dash",                   // made-up top-level domain
            "https://example.notarealtld",
            "https://example",
            "https://-bad-.com",
            "https://exa_mple.com",
            "https://example..com",
            // Only reachable from the creator's own machine / network
            "http://localhost:8080/",
            "http://127.0.0.1/",
            "http://0.0.0.0/",
            "http://10.0.0.5/",
            "http://172.16.4.2/",
            "http://192.168.1.1/router",
            "http://169.254.169.254/latest/meta-data", // cloud metadata endpoint
            "http://100.64.0.1/",                     // carrier-grade NAT
            "http://[::1]/",
            "http://[fe80::1]/",
            "http://[fd12:3456:789a::1]/",           // IPv6 unique local
            "http://224.0.0.1/",                     // multicast
            // Wrong scheme / not a URL
            "ftp://example.com/file",
            "javascript:alert(1)",
            "data:text/html,hi",
            "file:///etc/passwd",
            "example.com",
            "/relative/path",
            "https://",
            "not a url",
    })
    void rejectsUrlsThatAreNotPubliclyReachable(String url) {
        assertThat(validator.isValid(url, null)).as(url).isFalse();
    }

    @Test
    void nullIsLeftToNotBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
