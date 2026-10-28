package dev.bethge.shipir.common.net;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Central SSRF policy for every connection whose target a user can influence (git URLs, image-reference registries and
 * their token realms, OIDC issuers, webhooks, AI base URLs). Reuse it instead of writing ad-hoc host checks:
 * <ul>
 *   <li>{@link #check(URI)} / {@link #check(String)}: fail fast before any connection (throws {@link EgressDeniedException});</li>
 *   <li>{@link EgressHttpClient}: HTTP client that validates and connects in one resolution (no DNS rebinding window);</li>
 *   <li>{@link #dnsResolver()}: the same pinned resolution for other Apache HttpClient based code;</li>
 *   <li>{@link #resolve(String)}: for code that opens its own sockets.</li>
 * </ul>
 * Denied: unspecified/any-local, 0.0.0.0/8, 192.0.0.0/24, 198.18.0.0/15, link-local (169.254/16, fe80::/10), multicast, broadcast/reserved, known cloud metadata
 * addresses and hostnames, and (unless {@code shipir.egress.allow-private-ranges}) loopback, RFC 1918, ULA and CGNAT.
 * IPv4-mapped and NAT64-embedded IPv4 addresses are judged by the IPv4 they carry. A host is denied if <em>any</em> of
 * its addresses is denied. A denied and an unresolvable host are indistinguishable to callers.
 */
@Component
@EnableConfigurationProperties(EgressProperties.class)
public class EgressPolicy {

    /** Name resolution seam; production uses the system resolver, tests inject rebinding answers. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final Set<String> METADATA_HOSTS = Set.of("metadata.google.internal", "metadata", "instance-data",
            "instance-data.ec2.internal");
    private static final Set<String> METADATA_ADDRESSES = Set.of("169.254.169.254", "169.254.170.2",
            "100.100.100.200", "192.0.0.192", "fd00:ec2:0:0:0:0:0:254");

    private final boolean allowPrivate;
    private final HostResolver resolver;

    @Autowired
    public EgressPolicy(EgressProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    public EgressPolicy(EgressProperties properties, HostResolver resolver) {
        this.allowPrivate = properties.allowPrivateRanges();
        this.resolver = resolver;
    }

    /** Resolves {@code host} once and returns the addresses; connect to these, never resolve the name again. */
    public InetAddress[] resolve(String host) throws EgressDeniedException {
        if (host == null || host.isBlank() || isMetadataHost(host)) {
            throw new EgressDeniedException();
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(unbracket(host));
        } catch (UnknownHostException | RuntimeException e) {
            throw new EgressDeniedException();
        }
        if (addresses == null || addresses.length == 0) {
            throw new EgressDeniedException();
        }
        for (InetAddress address : addresses) {
            if (!isAllowed(address)) {
                throw new EgressDeniedException();
            }
        }
        return addresses;
    }

    /** Throws unless every address of {@code host} is permitted. Not a substitute for pinned connects (rebinding). */
    public void check(String host) throws EgressDeniedException {
        resolve(host);
    }

    public void check(URI uri) throws EgressDeniedException {
        check(uri.getHost());
    }

    /** Adapter that makes Apache HttpClient validate and connect with a single lookup per connection. */
    public DnsResolver dnsResolver() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                try {
                    return EgressPolicy.this.resolve(host);
                } catch (EgressDeniedException e) {
                    UnknownHostException denied = new UnknownHostException("Outbound connection not permitted");
                    denied.initCause(e);
                    throw denied;
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return host;
            }
        };
    }

    public boolean isAllowed(InetAddress address) {
        InetAddress ip = embeddedIPv4(address);
        if (ip.isAnyLocalAddress() || ip.isLinkLocalAddress() || ip.isMulticastAddress()
                || METADATA_ADDRESSES.contains(ip.getHostAddress()) || isReserved(ip)) {
            return false;
        }
        return allowPrivate || !isPrivate(ip);
    }

    private static boolean isMetadataHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return METADATA_HOSTS.contains(h.endsWith(".") ? h.substring(0, h.length() - 1) : h);
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /** IPv4-mapped (::ffff:a.b.c.d is already an Inet4Address in Java) and NAT64 (64:ff9b::/96) carry an IPv4. */
    private static InetAddress embeddedIPv4(InetAddress address) {
        if (address instanceof Inet6Address v6) {
            byte[] b = v6.getAddress();
            boolean nat64 = b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
            if (nat64) {
                for (int i = 4; i < 12; i++) {
                    if (b[i] != 0) {
                        return address;
                    }
                }
                try {
                    return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                } catch (UnknownHostException e) {
                    return address;
                }
            }
        }
        return address;
    }

    private static boolean isReserved(InetAddress ip) {
        if (ip instanceof Inet4Address) {
            byte[] b = ip.getAddress();
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            int third = b[2] & 0xff;
            return first == 0 // 0.0.0.0/8 "this network"
                    || first >= 240 // 240/4 reserved, includes 255.255.255.255
                    || (first == 192 && second == 0 && third == 0) // 192.0.0.0/24 IETF protocol assignments
                    || (first == 198 && (second == 18 || second == 19)); // 198.18.0.0/15 benchmarking
        }
        return false;
    }

    private static boolean isPrivate(InetAddress ip) {
        if (ip.isLoopbackAddress() || ip.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = ip.getAddress();
        if (ip instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 100 && second >= 64 && second <= 127; // 100.64.0.0/10 CGNAT
        }
        return (b[0] & 0xfe) == 0xfc; // fc00::/7 unique local
    }
}
