package com.tenzin.url_shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator("unit-test-code-secret");

    @Test
    void codesAreSixBase62CharactersAndDeterministic() {
        for (long id = 1; id <= 1_000; id++) {
            String code = generator.codeFor(id);
            assertThat(code).hasSize(6).matches("[0-9A-Za-z]{6}");
            assertThat(generator.codeFor(id)).isEqualTo(code);
        }
    }

    @Test
    void differentIdsNeverShareACode() {
        Set<String> seen = new HashSet<>();
        for (long id = 1; id <= 100_000; id++) {
            assertThat(seen.add(generator.codeFor(id))).as("duplicate code for id %d", id).isTrue();
        }
    }

    /**
     * The cycle-walking Feistel must be an exact permutation of [0, 62^length). The 6-character
     * space is too big to check exhaustively, so check the same code path on 2- and 3-character
     * spaces (3,844 and 238,328 values; their Feistel domains of 2^12 and 2^18 need cycle walking too).
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void permutationIsABijectionOnTheWholeSpace(int length) {
        long size = ShortCodeGenerator.pow62(length);
        boolean[] hit = new boolean[(int) size];
        for (long value = 0; value < size; value++) {
            long permuted = generator.permute(value, length);
            assertThat(permuted).isBetween(0L, size - 1);
            assertThat(hit[(int) permuted]).as("%d maps onto an already used value", value).isFalse();
            hit[(int) permuted] = true;
        }
    }

    @Test
    void consecutiveIdsGiveUnrelatedCodes() {
        List<String> codes = LongStream.rangeClosed(1, 2_000).mapToObj(generator::codeFor).toList();

        // Sequential codes would all start with "0000"; scrambled ones use the whole alphabet.
        long distinctFirstChars = codes.stream().map(c -> c.charAt(0)).distinct().count();
        assertThat(distinctFirstChars).isGreaterThanOrEqualTo(55);
        assertThat(codes).isNotEqualTo(codes.stream().sorted().toList());
    }

    @Test
    void theSecretDecidesTheCodes() {
        ShortCodeGenerator other = new ShortCodeGenerator("a-different-code-secret");
        long same = LongStream.rangeClosed(1, 1_000)
                .filter(id -> generator.codeFor(id).equals(other.codeFor(id)))
                .count();
        assertThat(same).isZero();
    }

    @Test
    void blankSecretFallsBackToTheDevelopmentKey() {
        ShortCodeGenerator dev = new ShortCodeGenerator(ShortCodeGenerator.DEV_SECRET);
        assertThat(new ShortCodeGenerator("").codeFor(42)).isEqualTo(dev.codeFor(42));
        assertThat(new ShortCodeGenerator((String) null).codeFor(42)).isEqualTo(dev.codeFor(42));
    }

    @Test
    void codesGrowOnlyWhenTheSixCharacterSpaceIsUsedUp() {
        long sixCharSpace = ShortCodeGenerator.pow62(6); // 56,800,235,584
        assertThat(generator.codeFor(sixCharSpace - 1)).hasSize(6);
        assertThat(generator.codeFor(sixCharSpace)).hasSize(7);
        assertThat(generator.codeFor(ShortCodeGenerator.pow62(10) - 1)).hasSize(10);
    }

    @Test
    void rejectsIdsOutsideTheSupportedRange() {
        assertThatThrownBy(() -> generator.codeFor(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.codeFor(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.codeFor(ShortCodeGenerator.pow62(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isSafeToShareAcrossThreads() throws Exception {
        long[] ids = LongStream.rangeClosed(1, 5_000).toArray();
        List<String> expected = LongStream.of(ids).mapToObj(generator::codeFor).toList();
        List<String> parallel = LongStream.of(ids).parallel().mapToObj(generator::codeFor).toList();
        assertThat(parallel).isEqualTo(expected);
    }
}
