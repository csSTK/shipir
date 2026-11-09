package dev.bethge.shipir.compose.portainer;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.net.EgressDeniedException;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.compose.portainer.dto.PortainerEnvVar;
import dev.bethge.shipir.compose.portainer.dto.PortainerStackFile;
import dev.bethge.shipir.compose.portainer.dto.PortainerStackSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads standalone Compose stacks from a Portainer instance so they can be recreated as Shipir deployments. Nothing is
 * stored; the API token lives only for the duration of one call. Only the Compose file and environment are read.
 */
@Service
public class PortainerImportService {

    private static final int TYPE_SWARM = 1;
    private static final int TYPE_COMPOSE = 2;
    private static final int TYPE_KUBERNETES = 3;

    private final PortainerClient client;
    private final EgressPolicy egress;

    PortainerImportService(PortainerClient client, EgressPolicy egress) {
        this.client = client;
        this.egress = egress;
    }

    public List<PortainerStackSummary> listStacks(String url, String apiToken) {
        URI base = validate(url);
        JsonNode stacks = client.get(base, apiToken, "/api/stacks");
        if (!stacks.isArray()) {
            throw new PortainerException(HttpStatus.BAD_GATEWAY, "PORTAINER_BAD_RESPONSE",
                    "Portainer sent an unusable response");
        }
        List<PortainerStackSummary> out = new ArrayList<>();
        for (JsonNode s : stacks) {
            int type = s.path("Type").asInt();
            out.add(new PortainerStackSummary(s.path("Id").asLong(), s.path("Name").asString(""), typeName(type),
                    s.path("EndpointId").asLong(), type == TYPE_COMPOSE, type == TYPE_COMPOSE ? null : reason(type)));
        }
        return out;
    }

    @Auditable(action = "PORTAINER_IMPORT_FETCH", targetType = "PORTAINER_STACK",
            details = "{'stack': #result?.name, 'portainerHost': T(dev.bethge.shipir.compose.portainer.PortainerImportService).hostOf(#url)}")
    public PortainerStackFile fetchStack(String url, String apiToken, long stackId) {
        URI base = validate(url);
        JsonNode stack = client.getStack(base, apiToken, "/api/stacks/" + stackId);
        int type = stack.path("Type").asInt();
        if (type != TYPE_COMPOSE) {
            throw new PortainerException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_STACK", reason(type));
        }
        JsonNode file = client.getStack(base, apiToken, "/api/stacks/" + stackId + "/file");
        String yaml = file.path("StackFileContent").asString("");
        if (yaml.isBlank()) {
            throw new PortainerException(HttpStatus.BAD_GATEWAY, "PORTAINER_BAD_RESPONSE",
                    "Portainer sent an unusable response");
        }
        List<PortainerEnvVar> env = new ArrayList<>();
        for (JsonNode e : stack.path("Env")) {
            String key = e.path("name").asString("");
            if (!key.isBlank()) {
                env.add(new PortainerEnvVar(key, e.path("value").asString("")));
            }
        }
        return new PortainerStackFile(stack.path("Name").asString(""), yaml, env);
    }

    /** Host for the audit entry; never throws and never echoes its input (the URL may carry credentials). */
    public static String hostOf(String url) {
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? "" : host;
        } catch (URISyntaxException | RuntimeException e) {
            return "";
        }
    }

    /** http(s) only, no credentials/query/fragment in the URL, and the egress policy before any connection. */
    private URI validate(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (URISyntaxException e) {
            throw invalidUrl();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalidUrl();
        }
        try {
            egress.check(uri);
        } catch (EgressDeniedException e) {
            throw new PortainerException(HttpStatus.BAD_REQUEST, "EGRESS_DENIED",
                    "This address is not allowed (internal targets need shipir.egress.allow-private-ranges)");
        }
        String s = uri.toString();
        return URI.create(s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }

    private static PortainerException invalidUrl() {
        return new PortainerException(HttpStatus.BAD_REQUEST, "INVALID_URL",
                "Enter an http(s) URL of your Portainer, without credentials, query or fragment");
    }

    private static String typeName(int type) {
        return switch (type) {
            case TYPE_SWARM -> "swarm";
            case TYPE_COMPOSE -> "compose";
            case TYPE_KUBERNETES -> "kubernetes";
            default -> "unknown";
        };
    }

    private static String reason(int type) {
        return switch (type) {
            case TYPE_SWARM -> "Swarm stacks are not supported";
            case TYPE_KUBERNETES -> "Kubernetes stacks are not supported";
            default -> "Only standalone Compose stacks are supported";
        };
    }
}
