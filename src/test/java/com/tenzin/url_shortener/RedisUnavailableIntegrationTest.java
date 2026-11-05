package com.tenzin.url_shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tenzin.url_shortener.repository.UrlMappingRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Points the app at a port where nothing listens, i.e. Redis is down from startup.
 * The application must still start, create links and redirect, using PostgreSQL alone.
 */
@SpringBootTest(properties = "spring.data.redis.port=6390")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RedisUnavailableIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @Test
    void createAndRedirectStillWorkWithoutRedis() throws Exception {
        String body = mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://example.com/no-redis\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");

        try {
            // First redirect: tries Redis, fails, starts the backoff, served from PostgreSQL.
            // Its cost depends on how fast the OS refuses the connection (slow on Windows),
            // so it's only checked for correctness.
            redirectAndAssert(code);

            // Later redirects (inside the backoff window) must not touch Redis at all,
            // so they must be fast: no connection attempt, no timeout.
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                redirectAndAssert(code);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // A single Redis connect/command timeout is 500ms; five requests well under that
            // proves none of them waited on Redis.
            assertThat(elapsedMs).isLessThan(500);
        } finally {
            repository.findByShortCode(code).ifPresent(repository::delete);
        }
    }

    private void redirectAndAssert(String code) throws Exception {
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/no-redis"));
    }

    @Test
    void unknownCodeIsStill404() throws Exception {
        mockMvc.perform(get("/AzL8n0Y58m7")).andExpect(status().isNotFound());
    }
}
