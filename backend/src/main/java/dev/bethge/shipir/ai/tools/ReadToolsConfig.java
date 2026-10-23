package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.audit.AuditLogQueryService;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.StackDeploymentService;
import dev.bethge.shipir.compose.StackService;
import dev.bethge.shipir.host.HostOverviewService;
import dev.bethge.shipir.host.HostResourcesService;
import dev.bethge.shipir.host.HostService;
import dev.bethge.shipir.notification.NotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The read-only tools. Each one delegates to the same service the REST API uses, so results contain exactly
 * what that API returns to the same role (env values, tokens, PEMs and webhook URLs are never part of it).
 */
@Configuration(proxyBeanMethods = false)
class ReadToolsConfig {

    private static final Map<String, Object> UUID_PROP = Map.of("type", "string", "format", "uuid");

    private final ObjectMapper mapper;

    ReadToolsConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    AiTool listHostsTool(HostService hosts) {
        return read("list_hosts", "List the Docker environments (hosts) managed by Shipir.",
                schema(Map.of()), Role.VIEWER, (a, c) -> hosts.list());
    }

    @Bean
    AiTool hostOverviewTool(HostOverviewService overview) {
        return read("host_overview", "Live status of every environment: up/down, Docker version, container, image and volume counts.",
                schema(Map.of()), Role.VIEWER, (a, c) -> overview.overview());
    }

    @Bean
    AiTool listStacksTool(HostResourcesService resources) {
        return read("list_stacks", "List the Compose stacks running on one environment, and whether Shipir manages them.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.stacks(uuid(a, "hostId")));
    }

    @Bean
    AiTool getManagedStackTool(StackService stacks) {
        return read("get_managed_stack", "Details of one Shipir-managed stack: status, Compose YAML and the names (never the values) of its environment variables.",
                stackSchema(), Role.VIEWER, (a, c) -> stacks.get(uuid(a, "hostId"), uuid(a, "stackId")));
    }

    @Bean
    AiTool listStackVersionsTool(StackDeploymentService deployments) {
        return read("list_stack_versions", "Version history of a managed stack (source, commit, who created it, current, deployed).",
                stackSchema(), Role.VIEWER, (a, c) -> deployments.versions(uuid(a, "hostId"), uuid(a, "stackId")));
    }

    @Bean
    AiTool listContainersTool(HostResourcesService resources) {
        return read("list_containers", "List all containers (any state) on one environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.containers(uuid(a, "hostId")));
    }

    @Bean
    AiTool listImagesTool(HostResourcesService resources) {
        return read("list_images", "List the Docker images on one environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.images(uuid(a, "hostId")));
    }

    @Bean
    AiTool listNetworksTool(HostResourcesService resources) {
        return read("list_networks", "List the Docker networks on one environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.networks(uuid(a, "hostId")));
    }

    @Bean
    AiTool listVolumesTool(HostResourcesService resources) {
        return read("list_volumes", "List the Docker volumes on one environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.volumes(uuid(a, "hostId")));
    }

    @Bean
    AiTool listNotificationsTool(NotificationService notifications) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("unreadOnly", Map.of("type", "boolean", "description", "Only unread notifications (default false)."));
        props.put("page", Map.of("type", "integer", "minimum", 0, "description", "Zero-based page (default 0)."));
        props.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 100, "description", "Page size (default 20)."));
        return read("list_notifications", "The current user's notifications, newest first.",
                schema(props), Role.VIEWER, (a, c) -> notifications.list(c.userId(),
                        Boolean.TRUE.equals(a.get("unreadOnly")), intArg(a, "page", 0), intArg(a, "size", 20)));
    }

    @Bean
    AiTool listAuditLogTool(AuditLogQueryService audit) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("page", Map.of("type", "integer", "minimum", 0, "description", "Zero-based page (default 0)."));
        props.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 100, "description", "Page size (default 20)."));
        props.put("action", Map.of("type", "string", "description", "Exact action name, e.g. HOST_CREATE."));
        props.put("username", Map.of("type", "string", "description", "Only entries of this user."));
        props.put("from", Map.of("type", "string", "description", "ISO-8601 instant, inclusive."));
        props.put("to", Map.of("type", "string", "description", "ISO-8601 instant, inclusive."));
        return read("list_audit_log", "Audit log entries, newest first (administrators only).",
                schema(props), Role.ADMIN, (a, c) -> {
                    instant(a, "from");
                    instant(a, "to");
                    return audit.find(intArg(a, "page", 0), intArg(a, "size", 20),
                            (String) a.get("action"), (String) a.get("username"), (String) a.get("from"), (String) a.get("to"));
                });
    }

    private static void instant(Map<String, Object> args, String name) {
        if (args.get(name) instanceof String s && !s.isBlank()) {
            try {
                Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw new ToolArgumentException("'" + name + "' must be an ISO-8601 instant like 2026-01-31T12:00:00Z");
            }
        }
    }

    private AiTool read(String name, String description, Map<String, Object> parameters, Role role,
                        BiFunction<Map<String, Object>, ToolContext, Object> handler) {
        return new FunctionTool(name, description, parameters, role, false, handler, mapper::writeValueAsString);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required),
                "additionalProperties", false);
    }

    private static Map<String, Object> hostSchema() {
        return schema(Map.of("hostId", UUID_PROP), "hostId");
    }

    private static Map<String, Object> stackSchema() {
        return schema(Map.of("hostId", UUID_PROP, "stackId", UUID_PROP), "hostId", "stackId");
    }

    private static UUID uuid(Map<String, Object> args, String name) {
        return UUID.fromString((String) args.get(name));
    }

    private static int intArg(Map<String, Object> args, String name, int fallback) {
        return args.get(name) instanceof Number n ? n.intValue() : fallback;
    }
}
