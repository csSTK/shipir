package dev.bethge.shipir.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client address for throttling. {@code X-Forwarded-For} is only read when the direct peer is a configured
 * trusted proxy (exact IP or CIDR range); then the right-most entry that is not itself a trusted proxy wins, so a client cannot
 * pick its own address by prepending values.
 */
@Component
@EnableConfigurationProperties(AuthThrottleProperties.class)
public class ClientAddressResolver {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final List<Cidr> trustedProxies;

    ClientAddressResolver(AuthThrottleProperties properties) {
        this.trustedProxies = properties.trustedProxies().stream().map(Cidr::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null) {
            return "unknown";
        }
        if (!isTrusted(peer)) {
            return peer;
        }
        String header = request.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank()) {
            return peer;
        }
        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrusted(hop)) {
                return hop;
            }
        }
        return peer;
    }

    private boolean isTrusted(String address) {
        byte[] bytes = literal(address);
        return bytes != null && trustedProxies.stream().anyMatch(cidr -> cidr.contains(bytes));
    }

    /**
     * Strict IP literal to bytes, or null. Only four dotted decimal octets or a string containing ':' are
     * accepted, so nothing else (hostnames, {@code abc}, {@code 172.28.77}) can reach a DNS lookup.
     */
    private static byte[] literal(String address) {
        Matcher v4 = IPV4.matcher(address);
        if (v4.matches()) {
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(v4.group(i + 1));
                if (octet > 255) {
                    return null;
                }
                bytes[i] = (byte) octet;
            }
            return bytes;
        }
        if (address.indexOf(':') < 0 || !IPV6_CHARS.matcher(address).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(address).getAddress(); // contains ':', so a literal, no lookup
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Network address plus prefix length; a plain address is a full-length prefix. */
    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String value) {
            String trimmed = value.trim();
            int slash = trimmed.indexOf('/');
            String address = slash < 0 ? trimmed : trimmed.substring(0, slash);
            byte[] network = literal(address);
            if (network == null) {
                throw new IllegalArgumentException("shipir.security.trusted-proxies: not an IP or CIDR: " + value);
            }
            int prefix = network.length * 8;
            if (slash >= 0) {
                try {
                    prefix = Integer.parseInt(trimmed.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("shipir.security.trusted-proxies: bad prefix: " + value, e);
                }
                if (prefix < 0 || prefix > network.length * 8) {
                    throw new IllegalArgumentException("shipir.security.trusted-proxies: bad prefix: " + value);
                }
            }
            return new Cidr(network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
