package com.tenzin.url_shortener.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlNormalizerTest {

    private final HttpUrlValidator validator = new HttpUrlValidator();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '¦', value = {
            "https://fonts.googleapis.com/css?family=Roboto|Open+Sans ¦ https://fonts.googleapis.com/css?family=Roboto%7COpen+Sans",
            "https://example.com/search?q={term}                      ¦ https://example.com/search?q=%7Bterm%7D",
            "https://example.com/a^b                                  ¦ https://example.com/a%5Eb",
            "https://example.com/path with space                      ¦ https://example.com/path%20with%20space",
            "https://example.com/100%                                 ¦ https://example.com/100%25",
            "https://example.com/50%off                               ¦ https://example.com/50%25off",
            "https://münchen.de/                                      ¦ https://xn--mnchen-3ya.de/",
            "https://user@bücher.de:8443/x                            ¦ https://user@xn--bcher-kva.de:8443/x",
    })
    void fixesWhatBrowsersWouldAccept(String input, String expected) {
        String normalized = UrlNormalizer.normalize(input.strip());

        assertThat(normalized).isEqualTo(expected.strip());
        assertThat(validator.isValid(normalized, null)).as("valid after normalising").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/already%20encoded?x=%7C",
            "https://en.wikipedia.org/wiki/München",          // readable Unicode path is kept
            "https://www.amazon.in/s?k=usb+c&ref=nb_sb_noss",
            "https://example.com/#/route?x=1",
            "http://[2606:4700:4700::1111]/",
    })
    void leavesAlreadyValidUrlsUnchanged(String url) {
        assertThat(UrlNormalizer.normalize(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://not a url", "not a url", "javascript:alert(1)", "https://hjfktgdkyfluytliu"})
    void doesNotMakeInvalidUrlsValid(String url) {
        assertThat(validator.isValid(UrlNormalizer.normalize(url), null)).isFalse();
    }
}
