package com.tenzin.url_shortener.dto;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import org.apache.commons.validator.routines.DomainValidator;
import org.apache.commons.validator.routines.InetAddressValidator;

/**
 * Accepts only absolute http(s) URLs whose host is something the public can actually reach:
 * <ul>
 *   <li>a domain name ending in a real top-level domain from the IANA list ({@code example.com},
 *       {@code shop.example.co.in}, {@code xn--mnchen-3ya.de}); or</li>
 *   <li>a public IPv4/IPv6 address.</li>
 * </ul>
 * Rejected: bare words ({@code https://hjfktgdkyfluytliu}), made-up TLDs ({@code server.dash}),
 * {@code localhost}, private/loopback/link-local IPs (a short link to them would only work on
 * the creator's own machine or network), dangerous schemes ({@code javascript:}, {@code data:},
 * {@code file:}), relative paths, and strings that aren't RFC 3986 URIs.
 *
 * <p>Purely offline: no DNS lookups, so validation is instant and can't fail because of the network.
 */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** allowLocal=false: "localhost" and "localdomain" are not acceptable hosts. */
    private static final DomainValidator DOMAINS = DomainValidator.getInstance(false);
    private static final InetAddressValidator IP_ADDRESSES = InetAddressValidator.getInstance();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                || host == null || host.isBlank()) {
            return false;
        }
        return isPublicHost(host);
    }

    static boolean isPublicHost(String host) {
        // URI keeps IPv6 literals in brackets: [2001:db8::1]
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (IP_ADDRESSES.isValid(bare)) {
            return isPublicIp(bare);
        }
        // A fully-qualified trailing dot ("example.com.") is legal DNS; validate without it.
        String domain = bare.endsWith(".") ? bare.substring(0, bare.length() - 1) : bare;
        return DOMAINS.isValid(domain);
    }

    private static boolean isPublicIp(String literal) {
        InetAddress address;
        try {
            // Safe: the input is already known to be an IP literal, so this parses without DNS.
            address = InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            return false;
        }
        if (address.isAnyLocalAddress()        // 0.0.0.0, ::
                || address.isLoopbackAddress()  // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress() // 169.254.0.0/16, fe80::/10
                || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16, fec0::/10
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return false; // IPv6 unique local fc00::/7 (the modern "private" range)
        }
        if (bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64) {
            return false; // 100.64.0.0/10 carrier-grade NAT, not publicly routable
        }
        return true;
    }
}
