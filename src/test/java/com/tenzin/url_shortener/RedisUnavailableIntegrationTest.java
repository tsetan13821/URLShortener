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
            // Several redirects: the first hits the Redis failure and starts the backoff,
            // the rest skip Redis entirely. All must be served from PostgreSQL.
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(get("/" + code))
                        .andExpect(status().isFound())
                        .andExpect(header().string("Location", "https://example.com/no-redis"));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // Generous bound: proves we aren't paying a Redis timeout on every request.
            assertThat(elapsedMs).isLessThan(2_000);
        } finally {
            repository.findByShortCode(code).ifPresent(repository::delete);
        }
    }

    @Test
    void unknownCodeIsStill404() throws Exception {
        mockMvc.perform(get("/AzL8n0Y58m7")).andExpect(status().isNotFound());
    }
}
