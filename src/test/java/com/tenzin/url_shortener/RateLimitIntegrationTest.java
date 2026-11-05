package com.tenzin.url_shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.jayway.jsonpath.JsonPath;
import com.tenzin.url_shortener.repository.UrlMappingRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Rate limiting end to end against the real Redis, with a small limit and a short window. */
@SpringBootTest(properties = {
        "app.rate-limit.create-limit=3",
        "app.rate-limit.window=3s",
        "app.rate-limit.key-prefix=test:rl:it:",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    private static final String CLIENT_A = "203.0.113.10";
    private static final String CLIENT_B = "203.0.113.11";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private UrlMappingRepository repository;

    private final List<String> createdCodes = new ArrayList<>();

    @BeforeEach
    void freshCounters() {
        Set<String> keys = redis.keys("test:rl:it:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void deleteCreatedLinks() {
        createdCodes.forEach(code -> repository.findByShortCode(code).ifPresent(repository::delete));
    }

    @Test
    void fourthCreateInTheWindowIsRefusedWith429AndRetryAfter() throws Exception {
        for (int i = 1; i <= 3; i++) {
            create(CLIENT_A, "https://example.com/rl-" + i).andExpect(status().isCreated());
        }

        create(CLIENT_A, "https://example.com/rl-4")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-3]")))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.windowSeconds").value(3))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "You can create up to 3 links per 3 seconds. Try again in")));

        // The refused request created nothing.
        assertThat(createdCodes).hasSize(3);
    }

    @Test
    void eachClientHasItsOwnAllowance() throws Exception {
        for (int i = 1; i <= 3; i++) {
            create(CLIENT_A, "https://example.com/a-" + i).andExpect(status().isCreated());
        }
        create(CLIENT_A, "https://example.com/a-4").andExpect(status().isTooManyRequests());

        create(CLIENT_B, "https://example.com/b-1").andExpect(status().isCreated());
    }

    @Test
    void redirectsAndStatsAreNeverLimited() throws Exception {
        for (int i = 1; i <= 3; i++) {
            create(CLIENT_A, "https://example.com/r-" + i).andExpect(status().isCreated());
        }
        create(CLIENT_A, "https://example.com/r-4").andExpect(status().isTooManyRequests());

        String code = createdCodes.getFirst();
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/" + code).with(from(CLIENT_A))).andExpect(status().isFound());
            mockMvc.perform(get("/api/urls/" + code + "/stats").with(from(CLIENT_A))).andExpect(status().isOk());
        }
    }

    @Test
    void invalidUrlsDoNotUseUpTheAllowance() throws Exception {
        for (int i = 0; i < 5; i++) {
            create(CLIENT_A, "not a url").andExpect(status().isBadRequest());
        }
        for (int i = 1; i <= 3; i++) {
            create(CLIENT_A, "https://example.com/v-" + i).andExpect(status().isCreated());
        }
    }

    @Test
    void allowanceComesBackWhenTheWindowEnds() throws Exception {
        for (int i = 1; i <= 3; i++) {
            create(CLIENT_A, "https://example.com/w-" + i).andExpect(status().isCreated());
        }
        create(CLIENT_A, "https://example.com/w-4").andExpect(status().isTooManyRequests());

        // Window is 3s: poll rather than sleep a fixed time, giving up well after it should have reset.
        long deadline = System.nanoTime() + 7_000_000_000L;
        int statusCode;
        do {
            Thread.sleep(200);
            statusCode = create(CLIENT_A, "https://example.com/w-again").andReturn().getResponse().getStatus();
        } while (statusCode == 429 && System.nanoTime() < deadline);
        assertThat(statusCode).isEqualTo(201);
    }

    private ResultActions create(String clientIp, String url) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/urls").with(from(clientIp))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\": \"" + url + "\"}"));
        var response = result.andReturn().getResponse();
        if (response.getStatus() == 201) {
            createdCodes.add(JsonPath.read(response.getContentAsString(), "$.shortCode"));
        }
        return result;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
