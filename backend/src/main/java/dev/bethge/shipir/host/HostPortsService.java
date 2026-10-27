package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.host.dto.HostPortsResponse;
import dev.bethge.shipir.host.dto.HostPortsResponse.Suggestion;
import dev.bethge.shipir.host.dto.HostPortsResponse.UsedPort;
import dev.bethge.shipir.permission.CurrentAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Which host ports are taken on an environment and the next free one for each wanted port. A port counts as taken
 * regardless of protocol, so a suggestion is free for tcp and udp alike. Hosts visible only through deployment grants
 * get suggestions but no list of used ports (other containers stay hidden).
 */
@Service
public class HostPortsService {

    static final int MAX_PREFERRED = 50;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final Set<String> HOLDING_STATES = Set.of("running", "created", "restarting", "paused");

    private final HostResourcesService resources;
    private final CurrentAccess access;

    HostPortsService(HostResourcesService resources, CurrentAccess access) {
        this.resources = resources;
        this.access = access;
    }

    public HostPortsResponse report(UUID hostId, String preferredRaw) {
        List<Integer> preferred = parsePreferred(preferredRaw);
        List<ContainerDto> all = resources.allContainers(hostId);
        Set<UsedPort> used = usedPorts(all.stream()
                .filter(c -> c.state() != null && HOLDING_STATES.contains(c.state()))
                .flatMap(c -> c.ports() == null ? Stream.<String>empty() : c.ports().stream())
                .toList());
        Set<Integer> taken = new HashSet<>();
        used.forEach(u -> taken.add(u.port()));
        List<UsedPort> shown = access.hostFullyVisible(hostId)
                ? used.stream().sorted(Comparator.comparingInt(UsedPort::port).thenComparing(UsedPort::protocol)).toList()
                : List.of();
        return new HostPortsResponse(shown, suggest(preferred, taken));
    }

    /** Host ports from entries like {@code 8080:80/tcp}, {@code 9000-9002:9000-9002/udp}, {@code 0.0.0.0:80:80/tcp}. */
    static Set<UsedPort> usedPorts(List<String> entries) {
        Set<UsedPort> result = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            String protocol = "tcp";
            String body = entry;
            int slash = entry.lastIndexOf('/');
            if (slash >= 0) {
                protocol = entry.substring(slash + 1).toLowerCase(Locale.ROOT);
                body = entry.substring(0, slash);
            }
            String[] parts = body.split(":");
            if (parts.length < 2) {
                continue; // exposed only, not published
            }
            int[] range = parseRange(parts[parts.length - 2]);
            if (range == null) {
                continue;
            }
            for (int p = range[0]; p <= range[1]; p++) {
                result.add(new UsedPort(p, protocol));
            }
        }
        return result;
    }

    private static int[] parseRange(String text) {
        try {
            int dash = text.indexOf('-');
            int from = Integer.parseInt(dash < 0 ? text : text.substring(0, dash));
            int to = dash < 0 ? from : Integer.parseInt(text.substring(dash + 1));
            if (from < MIN_PORT || to > MAX_PORT || from > to) {
                return null;
            }
            return new int[] {from, to};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Each wanted port keeps its number if free, else gets the next free one above (then below); no two share one. */
    static List<Suggestion> suggest(List<Integer> preferred, Set<Integer> taken) {
        Set<Integer> blocked = new HashSet<>(taken);
        List<Suggestion> result = new ArrayList<>();
        for (int wanted : preferred) {
            Integer free = null;
            for (int p = wanted; p <= MAX_PORT && free == null; p++) {
                if (!blocked.contains(p)) {
                    free = p;
                }
            }
            for (int p = wanted - 1; p >= MIN_PORT && free == null; p--) {
                if (!blocked.contains(p)) {
                    free = p;
                }
            }
            if (free != null) {
                blocked.add(free);
            }
            result.add(new Suggestion(wanted, free));
        }
        return result;
    }

    static List<Integer> parsePreferred(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] tokens = raw.split(",", -1);
        if (tokens.length > MAX_PREFERRED) {
            throw invalid("At most " + MAX_PREFERRED + " ports can be requested");
        }
        List<Integer> ports = new ArrayList<>();
        for (String token : tokens) {
            String t = token.trim();
            try {
                int port = Integer.parseInt(t);
                if (port < MIN_PORT || port > MAX_PORT || !t.matches("\\d{1,5}")) {
                    throw invalid("Invalid port: " + t);
                }
                ports.add(port);
            } catch (NumberFormatException e) {
                throw invalid("Invalid port: " + t);
            }
        }
        return ports;
    }

    private static HostException invalid(String message) {
        return new HostException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }
}
