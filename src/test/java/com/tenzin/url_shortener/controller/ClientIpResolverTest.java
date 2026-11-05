package com.tenzin.url_shortener.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.tenzin.url_shortener.config.AppProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private static ClientIpResolver resolver(String header) {
        return new ClientIpResolver(new AppProperties("http://sho.rt",
                new AppProperties.Cache(Duration.ofHours(1), "url:", Duration.ofSeconds(10)),
                new AppProperties.Clicks("clicks:pending", Duration.ofSeconds(30)),
                new AppProperties.RateLimit(true, 20, Duration.ofMinutes(1), "rl:", header),
                new AppProperties.Codes("")));
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void withoutHeaderConfiguredUsesTheConnectionAddressAndIgnoresHeaders() {
        MockHttpServletRequest request = request("198.51.100.4");
        request.addHeader("True-Client-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(resolver("").resolve(request)).isEqualTo("198.51.100.4");
        assertThat(resolver(null).resolve(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void usesTheConfiguredHeader() {
        MockHttpServletRequest request = request("10.0.0.1"); // the proxy
        request.addHeader("True-Client-IP", "203.0.113.9");
        assertThat(resolver("True-Client-IP").resolve(request)).isEqualTo("203.0.113.9");

        MockHttpServletRequest v6 = request("10.0.0.1");
        v6.addHeader("True-Client-IP", " 2001:db8::7 ");
        assertThat(resolver("True-Client-IP").resolve(v6)).isEqualTo("2001:db8::7");
    }

    @Test
    void fallsBackToTheConnectionAddressWhenTheHeaderIsMissingOrNotAnIp() {
        ClientIpResolver resolver = resolver("True-Client-IP");
        assertThat(resolver.resolve(request("10.0.0.1"))).isEqualTo("10.0.0.1");

        for (String bad : new String[] {"203.0.113.9, 10.0.0.2", "evil.example", "", "999.1.1.1"}) {
            MockHttpServletRequest request = request("10.0.0.1");
            request.addHeader("True-Client-IP", bad);
            assertThat(resolver.resolve(request)).as(bad).isEqualTo("10.0.0.1");
        }
    }
}
