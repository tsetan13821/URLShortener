package com.tenzin.url_shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.tenzin.url_shortener.entity.UrlMapping;
import com.tenzin.url_shortener.repository.UrlMappingRepository;
import com.tenzin.url_shortener.service.Base62;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end through the real stack: HTTP -> controller -> service -> Redis + PostgreSQL
 * (the url_shortener_test database and "test:url:" Redis keys).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    private final List<String> createdCodes = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdCodes.forEach(code -> {
            redis.delete("test:url:" + code);
            repository.findByShortCode(code).ifPresent(repository::delete);
        });
    }

    @Test
    void createThenRedirect() throws Exception {
        String code = create("https://example.com/integration?x=1");

        assertThat(code).isEqualTo(Base62.encode(repository.findByShortCode(code).orElseThrow().getId()));

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/integration?x=1"));
    }

    @Test
    void createReturnsShortUrlBuiltFromConfiguredBaseUrl() throws Exception {
        mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://example.com/base\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortUrl").value(org.hamcrest.Matchers.startsWith("http://sho.rt/")))
                .andDo(result -> createdCodes.add(JsonPath.read(result.getResponse().getContentAsString(), "$.shortCode")));
    }

    @Test
    void createdCodesAreUniqueAndIncreasing() throws Exception {
        String first = create("https://example.com/1");
        String second = create("https://example.com/1"); // same URL -> new code by design

        assertThat(second).isNotEqualTo(first);
        assertThat(Base62.decode(second)).isGreaterThan(Base62.decode(first));
    }

    @Test
    void firstRedirectPopulatesRedisWithTtl() throws Exception {
        String code = create("https://example.com/cache-me");
        String key = "test:url:" + code;

        assertThat(redis.hasKey(key)).as("create does not write to the cache").isFalse();

        mockMvc.perform(get("/" + code)).andExpect(status().isFound());

        assertThat(redis.opsForValue().get(key)).isEqualTo("https://example.com/cache-me");
        Long ttlSeconds = redis.getExpire(key);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void cachedRedirectDoesNotNeedTheDatabaseRow() throws Exception {
        String code = create("https://example.com/served-from-cache");
        mockMvc.perform(get("/" + code)).andExpect(status().isFound()); // warms the cache

        // Remove the row: if the next redirect still works, it was served by Redis.
        UrlMapping row = repository.findByShortCode(code).orElseThrow();
        repository.delete(row);

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/served-from-cache"));
    }

    @Test
    void unknownCodeReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/AzL8n0Y58m7")) // Long.MAX_VALUE: valid format, never issued
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Short URL not found"))
                .andExpect(jsonPath("$.shortCode").value("AzL8n0Y58m7"));
    }

    @Test
    void homePageAndAssetsAreServed() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("index.html"));

        for (String asset : List.of("/index.html", "/styles.css", "/app.js", "/favicon.svg")) {
            mockMvc.perform(get(asset))
                    .andExpect(status().isOk())
                    // Browsers must revalidate, so a new deploy is never served from a stale cache.
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
        }
    }

    @Test
    void notFoundPageIsNotPubliclyServedAsAFile() throws Exception {
        mockMvc.perform(get("/pages/not-found.html")).andExpect(status().isNotFound());
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
