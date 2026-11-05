package com.tenzin.url_shortener;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Pages, API responses, errors and the health check all carry the headers. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/app.js", "/AzL8n0Y58m7", "/not-a-code", "/actuator/health"})
    void everyResponseHasSecurityHeaders(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void hstsOnlyOverHttps() throws Exception {
        mockMvc.perform(get("/")).andExpect(header().doesNotExist("Strict-Transport-Security"));
        mockMvc.perform(get("/").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000"));
    }

    /** The CSP forbids inline scripts, so no page may contain one. */
    @Test
    void pagesUseNoInlineScripts() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(not(containsString("<script>"))));
        mockMvc.perform(get("/no-such-page").header("Accept", "text/html"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("<script src=\"/not-found.js\"></script>")))
                .andExpect(content().string(not(containsString("<script>"))));
        mockMvc.perform(get("/not-found.js")).andExpect(status().isOk());
    }

    @Test
    void livenessProbeIsExposed() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"UP\"")));
    }
}
