package com.tenzin.url_shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Base62Test {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "0, 0",
            "1, 1",
            "9, 9",
            "10, A",
            "35, Z",
            "36, a",
            "61, z",
            "62, 10",
            "3843, zz",
            "3844, 100",
            "9223372036854775807, AzL8n0Y58m7" // Long.MAX_VALUE
    })
    void encodesKnownValues(long value, String expected) {
        assertThat(Base62.encode(value)).isEqualTo(expected);
        assertThat(Base62.decode(expected)).isEqualTo(value);
    }

    @Test
    void longestCodeFitsTheColumn() {
        assertThat(Base62.encode(Long.MAX_VALUE)).hasSize(11);
    }

    @Test
    void roundTripsRandomValues() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 10_000; i++) {
            long value = random.nextLong(0, Long.MAX_VALUE);
            assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
        }
    }

    @Test
    void consecutiveIdsGiveDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (long id = 1; id <= 100_000; id++) {
            codes.add(Base62.encode(id));
        }
        assertThat(codes).hasSize(100_000);
    }

    @Test
    void outputUsesOnlyUrlSafeCharacters() {
        assertThat(Base62.encode(Long.MAX_VALUE)).matches("[0-9A-Za-z]+");
        assertThat(Base62.ALPHABET).hasSize(62);
        assertThat(Base62.ALPHABET.chars().distinct().count()).isEqualTo(62);
    }

    @Test
    void rejectsNegativeValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> Base62.encode(-1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc-def", "abc def", "ü", "zzzzzzzzzzzz" /* 12 chars */})
    void rejectsInvalidInput(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> Base62.decode(input));
    }

    @Test
    void rejectsNullInput() {
        assertThatIllegalArgumentException().isThrownBy(() -> Base62.decode(null));
    }

    @Test
    void rejectsValuesThatOverflowLong() {
        // 11 chars, but larger than Long.MAX_VALUE ("AzL8n0Y58m7").
        assertThatIllegalArgumentException().isThrownBy(() -> Base62.decode("AzL8n0Y58m8"));
        assertThatIllegalArgumentException().isThrownBy(() -> Base62.decode("zzzzzzzzzzz"));
    }
}
