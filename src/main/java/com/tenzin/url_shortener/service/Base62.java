package com.tenzin.url_shortener.service;

/**
 * Base62 encoding of non-negative {@code long} values using the URL-safe alphabet
 * {@code [0-9A-Za-z]}. No padding, so small IDs give short codes:
 * 1 -> "1", 61 -> "z", 62 -> "10", 3_844 -> "100", Long.MAX_VALUE -> "AzL8n0Y58m7" (11 chars).
 */
public final class Base62 {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();
    private static final int MAX_LENGTH = 11;

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Base62 can only encode non-negative values: " + value);
        }
        if (value == 0) {
            return "0";
        }
        char[] buffer = new char[MAX_LENGTH];
        int pos = MAX_LENGTH;
        while (value > 0) {
            buffer[--pos] = ALPHABET.charAt((int) (value % BASE));
            value /= BASE;
        }
        return new String(buffer, pos, MAX_LENGTH - pos);
    }

    public static long decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid Base62 string: " + encoded);
        }
        long result = 0;
        for (int i = 0; i < encoded.length(); i++) {
            int digit = digitOf(encoded.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base62 character '" + encoded.charAt(i) + "' in: " + encoded);
            }
            // Guard against values above Long.MAX_VALUE (e.g. "zzzzzzzzzzz").
            if (result > (Long.MAX_VALUE - digit) / BASE) {
                throw new IllegalArgumentException("Base62 value overflows a long: " + encoded);
            }
            result = result * BASE + digit;
        }
        return result;
    }

    private static int digitOf(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 36;
        }
        return -1;
    }
}
