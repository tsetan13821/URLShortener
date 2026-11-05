package com.tenzin.url_shortener.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.tenzin.url_shortener.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns a sequence ID into a short code that doesn't reveal the ID or its neighbours.
 *
 * <p>The ID is run through a keyed <b>Feistel permutation</b> (a format-preserving cipher) over the
 * space of 6-character Base62 codes (62^6 ≈ 56.8 billion), then Base62-encoded and left-padded to
 * 6 characters. A permutation maps every ID to a different code, so codes are still unique by
 * construction (no random collisions, no retries); but without the secret, code 1 tells you nothing
 * about code 2, so links can't be enumerated by counting ({@code /1}, {@code /2}, ...).
 *
 * <p>Details:
 * <ul>
 *   <li>The Feistel network works on an even number of bits (36 for 6 characters, two 18-bit
 *       halves), slightly more than 62^6. Values that land outside 62^6 are fed through again
 *       ("cycle walking") until they land inside; this keeps it a permutation of exactly 62^6.</li>
 *   <li>Each of the 8 rounds uses HMAC-SHA256(secret, round, length, half) as its round function.</li>
 *   <li>After 56.8 billion IDs, codes grow to 7 characters (a separate permutation over 62^7), and
 *       so on. Different lengths can never collide.</li>
 * </ul>
 *
 * <p>This is obscurity, not access control: anyone holding a short link can still open it. It stops
 * enumeration and hides how many links exist; it doesn't make a link private.
 */
@Component
public class ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeGenerator.class);

    /** Length of new codes until the 6-character space is used up. */
    static final int MIN_LENGTH = 6;

    /** 62^10 is the largest power of 62 whose Feistel domain (60 bits) still fits a long. */
    static final int MAX_LENGTH = 10;

    private static final int ROUNDS = 8;

    /** Used only when no secret is configured, i.e. local development. Public, so not secret. */
    static final String DEV_SECRET = "dev-only-not-secret";

    private final Mac prototype;

    @Autowired
    public ShortCodeGenerator(AppProperties properties) {
        this(properties.codes().secret());
    }

    ShortCodeGenerator(String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("CODE_SECRET is not set: using a built-in development key, so anyone can work out "
                    + "which ID a short code belongs to. Set CODE_SECRET (16+ characters) in any real deployment.");
            secret = DEV_SECRET;
        }
        try {
            prototype = Mac.getInstance("HmacSHA256");
            prototype.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // part of every JDK
        }
    }

    /** The short code for a sequence ID (> 0). Same ID and secret always give the same code. */
    public String codeFor(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("IDs start at 1: " + id);
        }
        int length = MIN_LENGTH;
        while (id >= pow62(length)) {
            length++;
            if (length > MAX_LENGTH) {
                throw new IllegalStateException("ID too large for a short code: " + id);
            }
        }
        long permuted = permute(id, length);
        return pad(Base62.encode(permuted), length);
    }

    /** Bijection on [0, 62^length): Feistel over the smallest even-bit domain, plus cycle walking. */
    long permute(long value, int length) {
        long size = pow62(length);
        int halfBits = (64 - Long.numberOfLeadingZeros(size - 1) + 1) / 2;
        Mac mac = cloneMac();
        long result = value;
        do {
            result = feistel(mac, result, halfBits, length);
        } while (result >= size);
        return result;
    }

    private static long feistel(Mac mac, long value, int halfBits, int length) {
        long mask = (1L << halfBits) - 1;
        long left = value >>> halfBits;
        long right = value & mask;
        for (int round = 0; round < ROUNDS; round++) {
            long next = left ^ (roundFunction(mac, round, length, right) & mask);
            left = right;
            right = next;
        }
        return (left << halfBits) | right;
    }

    private static long roundFunction(Mac mac, int round, int length, long half) {
        byte[] input = ByteBuffer.allocate(10).put((byte) round).put((byte) length).putLong(half).array();
        return ByteBuffer.wrap(mac.doFinal(input)).getLong();
    }

    private Mac cloneMac() {
        try {
            return (Mac) prototype.clone(); // Mac isn't thread-safe; a clone keeps the initialised key
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    static long pow62(int length) {
        long result = 1;
        for (int i = 0; i < length; i++) {
            result *= 62;
        }
        return result;
    }

    private static String pad(String code, int length) {
        return "0".repeat(length - code.length()) + code;
    }
}
