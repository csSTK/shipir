package dev.bethge.shipir.compose.generator;

import dev.bethge.shipir.common.util.DnsNames;
import dev.bethge.shipir.common.util.PathValidationException;
import dev.bethge.shipir.common.util.PathValidationUtil;
import dev.bethge.shipir.compose.generator.model.ComposeSpec;
import dev.bethge.shipir.compose.generator.model.DomainSpec;
import dev.bethge.shipir.compose.generator.model.EnvVarSpec;
import dev.bethge.shipir.compose.generator.model.NetworkSpec;
import dev.bethge.shipir.compose.generator.model.PortSpec;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.compose.generator.model.ServiceSpec;
import dev.bethge.shipir.compose.generator.model.VolumeMountSpec;
import dev.bethge.shipir.compose.generator.model.VolumeSpec;
import org.springframework.stereotype.Service;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the wizard model into Compose YAML. Validates everything first (all violations are reported together),
 * then builds an ordered tree so the output is stable: services, networks, volumes; fixed key order per service.
 */
@Service
public class ComposeGeneratorService {

    // Deliberately stricter than Compose's ComposeOptions.NAME: generated service names are also DNS hostnames on the
    // Docker network (RFC 1123, no leading '_'), and network/volume names must start with a letter or digit in Docker.
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern DRIVER = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,62}");
    private static final Pattern LABEL_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-/]{0,254}");
    private static final Pattern IMAGE = Pattern.compile("[^\\s'\"\\\\]+");
    /** Traefik entrypoint and cert resolver names; they end up in labels, so a strict charset. */
    private static final Pattern TRAEFIK_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}");
    /** Docker's own network name rule (uppercase and dots allowed); no quotes, whitespace, slashes or colons. */
    private static final Pattern DOCKER_NETWORK = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    /** Compose's implicit project network; it cannot be declared as an external proxy network. */
    private static final String COMPOSE_DEFAULT_NETWORK = "default";
    private static final Set<String> RESTART = Set.of("no", "always", "on-failure", "unless-stopped");
    private static final Set<String> PROTOCOLS = Set.of("tcp", "udp");
    /** Only used for the syntax checks of PathValidationUtil; the real deployment directory is not known here. */
    private static final Path SYNTAX_BASE = Path.of("").toAbsolutePath();

    private final YAMLMapper yaml = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
            .build();

    public String generate(ComposeSpec spec) {
        return generate(spec, null);
    }

    /**
     * @param managedProxy the proxy Shipir manages on the target host, or null. Shipir's managed Caddy does not read
     *                     Docker labels, so Caddy label domains are rejected there (managed routes are the way).
     */
    public String generate(ComposeSpec spec, ProxyType managedProxy) {
        if (spec == null) {
            throw new InvalidComposeSpecException(List.of("spec: required"));
        }
        List<String> errors = new ArrayList<>();
        validate(spec, managedProxy, errors);
        if (!errors.isEmpty()) {
            throw new InvalidComposeSpecException(errors);
        }
        return yaml.writeValueAsString(toTree(spec));
    }

    // ---- validation ----

    private void validate(ComposeSpec spec, ProxyType managedProxy, List<String> errors) {
        Set<String> networks = declared("networks", orEmpty(spec.networks()).stream()
                .map(n -> n == null ? null : n.name()).toList(), errors);
        Set<String> volumes = declared("volumes", orEmpty(spec.volumes()).stream()
                .map(v -> v == null ? null : v.name()).toList(), errors);
        for (NetworkSpec n : orEmpty(spec.networks())) {
            if (n != null) {
                checkDriver("networks." + n.name(), n.driver(), n.external(), errors);
                if (n.external() && COMPOSE_DEFAULT_NETWORK.equals(n.name())) {
                    // Without a name override, Compose resolves an external "default" to a network Docker does not have.
                    errors.add("networks." + n.name() + ": 'default' is Compose's own network and cannot be external");
                }
            }
        }
        for (VolumeSpec v : orEmpty(spec.volumes())) {
            if (v != null) {
                checkDriver("volumes." + v.name(), v.driver(), v.external(), errors);
            }
        }
        if (spec.services() == null || spec.services().isEmpty()) {
            errors.add("services: at least one service is required");
            return;
        }
        Set<String> serviceNames = declared("services", spec.services().stream()
                .map(s -> s == null ? null : s.name()).toList(), errors);
        for (ServiceSpec s : spec.services()) {
            if (s != null && s.name() != null) {
                validateService(s, serviceNames, networks, volumes, managedProxy, errors);
            }
        }
        detectCycle(spec.services(), errors);
        detectDomainClashes(spec.services(), errors);
    }

    /** One domain per service and no two services may share a host or, for Traefik, a router name. */
    private void detectDomainClashes(List<ServiceSpec> services, List<String> errors) {
        Set<String> hosts = new HashSet<>();
        Map<String, String> routerIds = new LinkedHashMap<>();
        for (ServiceSpec s : services) {
            if (s == null || s.name() == null || s.domain() == null || s.domain().host() == null
                    || !DnsNames.isValidHostname(s.domain().host())) {
                continue;
            }
            String host = s.domain().host();
            String at = "services." + s.name() + ".domain.host";
            if (!hosts.add(host)) {
                errors.add(at + ": already used by another service");
            } else if (s.domain().proxy() == ProxyType.TRAEFIK) {
                String other = routerIds.putIfAbsent(routerId(host), host);
                if (other != null) {
                    errors.add(at + ": Traefik router name collides with the one of " + other);
                }
            }
        }
    }

    private static String routerId(String host) {
        return host.replace('.', '-');
    }

    private Set<String> declared(String scope, List<String> names, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (name == null || !NAME.matcher(name).matches()) {
                errors.add(scope + ": invalid name (lowercase letters, digits, '_' and '-', max 63 chars)");
            } else if (!seen.add(name)) {
                errors.add(scope + "." + name + ": duplicate name");
            }
        }
        return seen;
    }

    private void checkDriver(String where, String driver, boolean external, List<String> errors) {
        if (driver != null && !DRIVER.matcher(driver).matches()) {
            errors.add(where + ".driver: invalid driver name");
        }
        if (external && driver != null) {
            errors.add(where + ": an external resource cannot set a driver");
        }
    }

    private void validateService(ServiceSpec s, Set<String> serviceNames, Set<String> networks,
                                 Set<String> volumes, ProxyType managedProxy, List<String> errors) {
        String at = "services." + s.name();
        if (s.image() == null || !IMAGE.matcher(s.image()).matches()) {
            errors.add(at + ".image: required, no whitespace or quotes");
        }
        if (s.restart() != null && !RESTART.contains(s.restart())) {
            errors.add(at + ".restart: must be one of no, always, on-failure, unless-stopped");
        }
        for (String arg : orEmpty(s.command())) {
            if (arg == null) {
                errors.add(at + ".command: arguments must not be null");
            }
        }
        for (PortSpec p : orEmpty(s.ports())) {
            validatePort(at, p, errors);
        }
        Set<String> envKeys = new HashSet<>();
        for (EnvVarSpec e : orEmpty(s.environment())) {
            validateEnv(at, e, envKeys, errors);
        }
        for (VolumeMountSpec v : orEmpty(s.volumes())) {
            validateMount(at, v, volumes, errors);
        }
        for (String n : orEmpty(s.networks())) {
            if (n == null || !networks.contains(n)) {
                errors.add(at + ".networks: network is not declared at top level");
            }
        }
        for (Map.Entry<String, String> l : orEmpty(s.labels()).entrySet()) {
            if (l.getKey() == null || !LABEL_KEY.matcher(l.getKey()).matches()) {
                errors.add(at + ".labels: invalid label key");
            }
            if (l.getValue() == null) {
                errors.add(at + ".labels: value must not be null");
            }
        }
        if (s.domain() != null) {
            validateDomain(at, s, managedProxy, errors);
        }
        for (String d : orEmpty(s.dependsOn())) {
            if (d == null || !serviceNames.contains(d)) {
                errors.add(at + ".depends_on: unknown service");
            } else if (d.equals(s.name())) {
                errors.add(at + ".depends_on: a service cannot depend on itself");
            }
        }
    }

    /** Entrypoint and resolver names go into labels, so they get a strict charset; Traefik only. Blank means default. */
    private void validateTraefikName(String at, String value, ProxyType proxy, List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (proxy == ProxyType.CADDY) {
            errors.add(at + ": only applies to Traefik");
        } else if (!TRAEFIK_NAME.matcher(value).matches()) {
            errors.add(at + ": use letters, digits, dots, hyphens and underscores; start with a letter or digit");
        }
    }

    private void validateDomain(String at, ServiceSpec s, ProxyType managedProxy, List<String> errors) {
        DomainSpec d = s.domain();
        if (d.host() == null || !DnsNames.isValidHostname(d.host())) {
            errors.add(at + ".domain.host: must be a lower-case domain name such as app.example.com");
        }
        if (d.port() == null || !validPort(d.port())) {
            errors.add(at + ".domain.port: container port must be 1-65535");
        }
        if (d.proxy() == null) {
            errors.add(at + ".domain.proxy: must be TRAEFIK or CADDY");
        } else if (d.proxy() == ProxyType.CADDY && managedProxy == ProxyType.CADDY) {
            errors.add(at + ".domain.proxy: this environment has a managed Caddy proxy, which does not read Docker labels;"
                    + " add the domain under Domains instead");
        }
        if (d.network() != null && COMPOSE_DEFAULT_NETWORK.equals(d.network())) {
            // Compose's own project network; emitting it as "external: true" makes the file unresolvable.
            errors.add(at + ".domain.network: 'default' is Compose's own network; choose a network that exists on the host");
        } else if (d.network() != null && !DOCKER_NETWORK.matcher(d.network()).matches()) {
            errors.add(at + ".domain.network: invalid network name");
        }
        validateTraefikName(at + ".domain.entrypoint", d.entrypoint(), d.proxy(), errors);
        validateTraefikName(at + ".domain.certResolver", d.certResolver(), d.proxy(), errors);
        if (d.host() != null && d.port() != null && d.proxy() != null && DnsNames.isValidHostname(d.host())) {
            for (String key : proxyLabels(s.name(), d).keySet()) {
                if (orEmpty(s.labels()).containsKey(key)) {
                    errors.add(at + ".labels: label " + key + " is set by the domain and cannot be set manually");
                }
            }
        }
    }

    private void validatePort(String at, PortSpec p, List<String> errors) {
        if (p == null || p.container() == null || !validPort(p.container())) {
            errors.add(at + ".ports: container port must be 1-65535");
            return;
        }
        if (p.host() != null && !validPort(p.host())) {
            errors.add(at + ".ports: host port must be 1-65535");
        }
        if (p.protocol() != null && !PROTOCOLS.contains(p.protocol())) {
            errors.add(at + ".ports: protocol must be tcp or udp");
        }
    }

    private boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }

    private void validateEnv(String at, EnvVarSpec e, Set<String> keys, List<String> errors) {
        if (e == null || e.key() == null || !ENV_KEY.matcher(e.key()).matches()) {
            errors.add(at + ".environment: invalid variable name");
            return;
        }
        if (!keys.add(e.key())) {
            errors.add(at + ".environment." + e.key() + ": duplicate variable");
        }
        if ((e.value() == null) == (e.ref() == null)) {
            errors.add(at + ".environment." + e.key() + ": set exactly one of value or ref");
        } else if (e.ref() != null && !ENV_KEY.matcher(e.ref()).matches()) {
            errors.add(at + ".environment." + e.key() + ": invalid .env key reference");
        }
    }

    private void validateMount(String at, VolumeMountSpec v, Set<String> volumes, List<String> errors) {
        if (v == null || v.source() == null || v.target() == null) {
            errors.add(at + ".volumes: source and target are required");
            return;
        }
        if (!v.target().startsWith("/") || v.target().contains("\0") || v.target().contains(":")) {
            errors.add(at + ".volumes: target must be an absolute container path without ':'");
        }
        if (isBind(v.source())) {
            try {
                PathValidationUtil.resolve(SYNTAX_BASE, v.source());
            } catch (PathValidationException ex) {
                errors.add(at + ".volumes: bind path rejected (" + ex.getMessage() + ")");
            }
            if (v.source().contains(":")) {
                errors.add(at + ".volumes: bind path must not contain ':'");
            }
        } else if (!volumes.contains(v.source())) {
            errors.add(at + ".volumes: named volume is not declared at top level");
        }
    }

    private boolean isBind(String source) {
        return source.contains("/") || source.startsWith(".") || source.contains("\\") || source.contains("%");
    }

    private void detectCycle(List<ServiceSpec> services, List<String> errors) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (ServiceSpec s : services) {
            if (s != null && s.name() != null) {
                graph.putIfAbsent(s.name(), orEmpty(s.dependsOn()));
            }
        }
        Set<String> done = new HashSet<>();
        for (String start : graph.keySet()) {
            if (hasCycle(start, graph, new HashSet<>(), done)) {
                errors.add("services: depends_on contains a cycle");
                return;
            }
        }
    }

    private boolean hasCycle(String node, Map<String, List<String>> graph, Set<String> path, Set<String> done) {
        if (done.contains(node)) {
            return false;
        }
        if (!path.add(node)) {
            return true;
        }
        for (String next : graph.getOrDefault(node, List.of())) {
            if (next != null && !next.equals(node) && graph.containsKey(next) && hasCycle(next, graph, path, done)) {
                return true;
            }
        }
        path.remove(node);
        done.add(node);
        return false;
    }

    // ---- output ----

    private Map<String, Object> toTree(ComposeSpec spec) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> services = new LinkedHashMap<>();
        for (ServiceSpec s : spec.services()) {
            services.put(s.name(), serviceTree(s));
        }
        root.put("services", services);
        Map<String, Object> networks = new LinkedHashMap<>();
        for (NetworkSpec n : orEmpty(spec.networks())) {
            networks.put(n.name(), resourceTree(n.driver(), n.external()));
        }
        // A proxy network named by a domain but not declared is the proxy's own, so it already exists.
        for (ServiceSpec s : spec.services()) {
            if (s.domain() != null && s.domain().network() != null) {
                networks.putIfAbsent(s.domain().network(), resourceTree(null, true));
            }
        }
        if (!networks.isEmpty()) {
            root.put("networks", networks);
        }
        if (!orEmpty(spec.volumes()).isEmpty()) {
            Map<String, Object> volumes = new LinkedHashMap<>();
            for (VolumeSpec v : spec.volumes()) {
                volumes.put(v.name(), resourceTree(v.driver(), v.external()));
            }
            root.put("volumes", volumes);
        }
        return root;
    }

    /** Empty map renders as {@code {}}, which Compose accepts for a default resource. */
    private Map<String, Object> resourceTree(String driver, boolean external) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (driver != null) {
            m.put("driver", driver);
        }
        if (external) {
            m.put("external", true);
        }
        return m;
    }

    private Map<String, Object> serviceTree(ServiceSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("image", s.image());
        if (!orEmpty(s.command()).isEmpty()) {
            m.put("command", s.command().stream().map(ComposeGeneratorService::escapeDollar).toList());
        }
        if (s.restart() != null) {
            m.put("restart", s.restart());
        }
        if (!orEmpty(s.ports()).isEmpty()) {
            m.put("ports", s.ports().stream().map(this::portString).toList());
        }
        if (!orEmpty(s.environment()).isEmpty()) {
            Map<String, String> env = new LinkedHashMap<>();
            for (EnvVarSpec e : s.environment()) {
                env.put(e.key(), e.ref() != null ? "${" + e.ref() + "}" : escapeDollar(e.value()));
            }
            m.put("environment", env);
        }
        if (!orEmpty(s.volumes()).isEmpty()) {
            m.put("volumes", s.volumes().stream().map(this::mountString).toList());
        }
        List<String> networks = serviceNetworks(s);
        if (!networks.isEmpty()) {
            m.put("networks", networks);
        }
        Map<String, String> labels = new LinkedHashMap<>();
        orEmpty(s.labels()).forEach((k, v) -> labels.put(k, escapeDollar(v)));
        if (s.domain() != null) {
            labels.putAll(proxyLabels(s.name(), s.domain()));
        }
        if (!labels.isEmpty()) {
            m.put("labels", labels);
        }
        if (!orEmpty(s.dependsOn()).isEmpty()) {
            m.put("depends_on", s.dependsOn());
        }
        return m;
    }

    /**
     * Joining the proxy network must not cut the service off its project network: a service that lists networks
     * explicitly no longer gets "default", so it is added when the service listed none.
     */
    private List<String> serviceNetworks(ServiceSpec s) {
        List<String> networks = new ArrayList<>(orEmpty(s.networks()));
        if (s.domain() != null && s.domain().network() != null) {
            if (networks.isEmpty()) {
                networks.add("default");
            }
            if (!networks.contains(s.domain().network())) {
                networks.add(s.domain().network());
            }
        }
        return networks;
    }

    /**
     * Routing labels of a proxy. Traefik: router and service are named after the domain (unique per host, unlike the
     * Compose service name), TLS via the entrypoint and resolver of the domain, by default {@code websecure} and
     * {@code letsencrypt} of the bundled Traefik template. Caddy: caddy-docker-proxy labels.
     * The domain is already validated, so it holds no backtick.
     */
    private Map<String, String> proxyLabels(String service, DomainSpec d) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (d.proxy() == ProxyType.TRAEFIK) {
            String id = routerId(d.host());
            String router = "traefik.http.routers." + id;
            labels.put("traefik.enable", "true");
            if (d.network() != null) {
                labels.put("traefik.docker.network", d.network());
            }
            labels.put(router + ".rule", "Host(`" + d.host() + "`)");
            labels.put(router + ".entrypoints", orDefault(d.entrypoint(), DomainSpec.DEFAULT_ENTRYPOINT));
            labels.put(router + ".tls.certresolver",
                    orDefault(d.certResolver(), DomainSpec.DEFAULT_CERT_RESOLVER));
            labels.put("traefik.http.services." + id + ".loadbalancer.server.port", String.valueOf(d.port()));
        } else {
            labels.put("caddy", d.host());
            labels.put("caddy.reverse_proxy", "{{upstreams " + d.port() + "}}");
        }
        return labels;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String portString(PortSpec p) {
        String proto = p.protocol() == null ? "tcp" : p.protocol();
        String base = p.host() == null ? String.valueOf(p.container()) : p.host() + ":" + p.container();
        return base + "/" + proto;
    }

    private String mountString(VolumeMountSpec v) {
        String source = isBind(v.source()) && !v.source().startsWith("./") && !v.source().equals(".") ? "./" + v.source() : v.source();
        return source + ":" + v.target() + (v.readOnly() ? ":ro" : "");
    }

    /** A literal dollar sign must be doubled so Compose does not interpolate it. */
    private static String escapeDollar(String s) {
        return s.replace("$", "$$");
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> m) {
        return m == null ? Map.of() : m;
    }
}
