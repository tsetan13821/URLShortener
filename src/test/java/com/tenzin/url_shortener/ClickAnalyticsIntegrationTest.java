package com.tenzin.url_shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import com.tenzin.url_shortener.repository.UrlMappingRepository;
import com.tenzin.url_shortener.service.ClickCounter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Click counting end to end: redirect -> Redis -> flush -> PostgreSQL -> stats endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClickAnalyticsIntegrationTest {

    private static final String PENDING_KEY = "test:clicks:pending";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClickCounter clickCounter;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UrlMappingRepository repository;

    private final List<String> createdCodes = new ArrayList<>();

    @BeforeEach
    void startClean() {
        redis.delete(PENDING_KEY);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(PENDING_KEY);
        createdCodes.forEach(code -> {
            redis.delete("test:url:" + code);
            repository.findByShortCode(code).ifPresent(repository::delete);
        });
    }

    @Test
    void redirectsAreCountedInRedisThenFlushedToPostgres() throws Exception {
        String code = create("https://example.com/counted");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        }

        // Counted in Redis only: the redirect path didn't write to PostgreSQL.
        assertThat(redis.opsForHash().get(PENDING_KEY, code)).isEqualTo("3");
        assertThat(storedClicks(code)).isZero();
        // ...yet the stats endpoint is already current.
        mockMvc.perform(get("/api/urls/" + code + "/stats")).andExpect(jsonPath("$.clicks").value(3));

        clickCounter.flush();

        assertThat(storedClicks(code)).isEqualTo(3);
        assertThat(redis.hasKey(PENDING_KEY)).as("pending hash cleared by flush").isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT last_clicked_at IS NOT NULL FROM url_mappings WHERE short_code = ?", Boolean.class, code))
                .isTrue();
        // Same total after the flush: nothing lost, nothing counted twice.
        mockMvc.perform(get("/api/urls/" + code + "/stats")).andExpect(jsonPath("$.clicks").value(3));
    }

    @Test
    void flushesAccumulate() throws Exception {
        String code = create("https://example.com/accumulate");

        mockMvc.perform(get("/" + code));
        mockMvc.perform(get("/" + code));
        clickCounter.flush();
        mockMvc.perform(get("/" + code));
        clickCounter.flush();
        clickCounter.flush(); // empty flush is a no-op

        assertThat(storedClicks(code)).isEqualTo(3);
    }

    @Test
    void concurrentClicksAreNeitherLostNorDoubleCounted() throws Exception {
        String code = create("https://example.com/busy");
        int threads = 20;
        int clicksPerThread = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < clicksPerThread; i++) {
                    clickCounter.record(code);
                    if (i % 10 == 0) {
                        clickCounter.flush(); // flushes racing with clicks and with each other
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        clickCounter.flush();

        assertThat(storedClicks(code)).isEqualTo((long) threads * clicksPerThread);
    }

    @Test
    void headRequestsAndUnknownCodesAreNotCounted() throws Exception {
        String code = create("https://example.com/head");

        mockMvc.perform(head("/" + code)).andExpect(status().isFound());
        mockMvc.perform(get("/AzL8n0Y58m7")).andExpect(status().isNotFound());
        clickCounter.flush();

        assertThat(storedClicks(code)).isZero();
        assertThat(redis.hasKey(PENDING_KEY)).isFalse();
    }

    @Test
    void statsForUnknownCodeIs404() throws Exception {
        mockMvc.perform(get("/api/urls/AzL8n0Y58m7/stats")).andExpect(status().isNotFound());
    }

    private long storedClicks(String code) {
        return jdbc.queryForObject("SELECT click_count FROM url_mappings WHERE short_code = ?", Long.class, code);
    }

    private String create(String url) throws Exception {
        String body = mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.shortCode");
        createdCodes.add(code);
        return code;
    }
}
