package com.tenzin.url_shortener.dto;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans up a pasted URL the way a browser's address bar would, before it is validated and stored.
 *
 * <p>Browsers happily accept links such as {@code https://fonts.googleapis.com/css?family=Roboto|Open+Sans}
 * or {@code https://münchen.de}, but {@link java.net.URI} rejects them. This class:
 * <ul>
 *   <li>converts an internationalised host to its ASCII (punycode) form: {@code münchen.de} -> {@code xn--mnchen-3ya.de};</li>
 *   <li>percent-encodes ASCII characters that are illegal in a URI ({@code space " < > \ ^ ` { | }}, control
 *       characters) in the path, query and fragment;</li>
 *   <li>encodes a {@code %} that isn't the start of a valid {@code %XX} escape as {@code %25}.</li>
 * </ul>
 * Existing {@code %XX} escapes, reserved characters and non-ASCII letters in the path are left untouched,
 * so readable URLs stay readable. Anything that doesn't look like {@code scheme://authority...} is returned
 * unchanged and left for {@link HttpUrlValidator} to reject.
 */
public final class UrlNormalizer {

    /** scheme "://" authority, then the rest (path, query, fragment). */
    private static final Pattern URL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]*)(.*)$", Pattern.DOTALL);
    /** [userinfo@]host[:port], host not bracketed (IPv6 literals are left alone). */
    private static final Pattern AUTHORITY = Pattern.compile("^(?:([^@]*)@)?([^\\[\\]:]+)(:\\d*)?$");

    private static final String ILLEGAL_ASCII = " \"<>\\^`{|}";

    private UrlNormalizer() {
    }

    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = URL.matcher(url);
        if (!m.matches()) {
            return url;
        }
        return m.group(1) + "://" + normalizeAuthority(m.group(2)) + encodeIllegal(m.group(3));
    }

    private static String normalizeAuthority(String authority) {
        Matcher m = AUTHORITY.matcher(authority);
        if (!m.matches()) {
            return authority;
        }
        String host = m.group(2);
        if (host.chars().allMatch(c -> c < 0x80)) {
            return authority;
        }
        try {
            String ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            return (m.group(1) != null ? m.group(1) + "@" : "") + ascii + (m.group(3) != null ? m.group(3) : "");
        } catch (IllegalArgumentException e) {
            return authority; // not a valid IDN: let the validator reject it
        }
    }

    private static String encodeIllegal(String rest) {
        StringBuilder out = new StringBuilder(rest.length() + 16);
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '%') {
                boolean validEscape = i + 2 < rest.length()
                        && isHex(rest.charAt(i + 1)) && isHex(rest.charAt(i + 2));
                out.append(validEscape ? "%" : "%25");
            } else if (c < 0x20 || c == 0x7F || ILLEGAL_ASCII.indexOf(c) >= 0) {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
