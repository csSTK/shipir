package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.audit.AuditLogQueryService;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.git.GitCredentialService;
import dev.bethge.shipir.compose.DeploymentRunService;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.config_editor.ConfigFileService;
import dev.bethge.shipir.host.HostOverviewService;
import dev.bethge.shipir.host.HostResourcesService;
import dev.bethge.shipir.host.HostService;
import dev.bethge.shipir.notification.NotificationService;
import dev.bethge.shipir.permission.PermissionGrantService;
import dev.bethge.shipir.permission.TeamService;
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
        return read("list_hosts", "List environments (hosts).",
                schema(Map.of()), Role.VIEWER, (a, c) -> hosts.list());
    }

    @Bean
    AiTool hostOverviewTool(HostOverviewService overview) {
        return read("host_overview", "Live status and resource counts of every environment.",
                schema(Map.of()), Role.VIEWER, (a, c) -> overview.overview());
    }

    @Bean
    AiTool listDeploymentsTool(HostResourcesService resources) {
        return read("list_deployments", "List Compose deployments on an environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.deployments(uuid(a, "hostId")));
    }

    @Bean
    AiTool getManagedDeploymentTool(DeploymentService deployments) {
        return read("get_managed_deployment", "One managed deployment: status, Compose YAML, env variable names.",
                deploymentSchema(), Role.VIEWER, (a, c) -> deployments.get(uuid(a, "hostId"), uuid(a, "deploymentId")));
    }

    @Bean
    AiTool listDeploymentServicesTool(DeploymentService deployments) {
        return read("list_deployment_services", "Compose service and profile names of a managed deployment.",
                deploymentSchema(), Role.VIEWER, (a, c) -> deployments.services(uuid(a, "hostId"), uuid(a, "deploymentId")));
    }

    @Bean
    AiTool listConfigFilesTool(ConfigFileService files) {
        return read("list_config_files", "File tree of a managed deployment's directory.",
                deploymentSchema(), Role.VIEWER, (a, c) -> files.tree(uuid(a, "hostId"), uuid(a, "deploymentId")));
    }

    /** Env-like files are refused by the service whatever the role; the registry caps the size of the result. */
    @Bean
    AiTool readConfigFileTool(ConfigFileService files) {
        Map<String, Object> props = Map.of("hostId", UUID_PROP, "deploymentId", UUID_PROP,
                "path", Map.of("type", "string"));
        return read("read_config_file", "Text of a config file; path relative to the deployment directory.",
                schema(props, "hostId", "deploymentId", "path"), Role.VIEWER,
                (a, c) -> files.readWithoutSecrets(uuid(a, "hostId"), uuid(a, "deploymentId"), (String) a.get("path")));
    }

    @Bean
    AiTool listDeploymentVersionsTool(DeploymentRunService deployments) {
        return read("list_deployment_versions", "Version history of a managed deployment.",
                deploymentSchema(), Role.VIEWER, (a, c) -> deployments.versions(uuid(a, "hostId"), uuid(a, "deploymentId")));
    }

    @Bean
    AiTool listContainersTool(HostResourcesService resources) {
        return read("list_containers", "List containers (any state) on an environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.containers(uuid(a, "hostId")));
    }

    @Bean
    AiTool listImagesTool(HostResourcesService resources) {
        return read("list_images", "List images on an environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.images(uuid(a, "hostId")));
    }

    @Bean
    AiTool listNetworksTool(HostResourcesService resources) {
        return read("list_networks", "List networks on an environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.networks(uuid(a, "hostId")));
    }

    @Bean
    AiTool listVolumesTool(HostResourcesService resources) {
        return read("list_volumes", "List volumes on an environment.",
                hostSchema(), Role.VIEWER, (a, c) -> resources.volumes(uuid(a, "hostId")));
    }

    @Bean
    AiTool listNotificationsTool(NotificationService notifications) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("unreadOnly", Map.of("type", "boolean"));
        props.put("page", Map.of("type", "integer", "minimum", 0));
        props.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 100));
        return read("list_notifications", "Own notifications, newest first (page is zero-based).",
                schema(props), Role.VIEWER, (a, c) -> notifications.list(c.userId(),
                        Boolean.TRUE.equals(a.get("unreadOnly")), intArg(a, "page", 0), intArg(a, "size", 20)));
    }

    /** Only id and name: an OPERATOR may reference a credential on create but never sees anything secret about it. */
    @Bean
    AiTool listGitCredentialsTool(GitCredentialService credentials) {
        return read("list_git_credentials", "Git credential ids and names, for create_deployment (GIT, private repos).",
                schema(Map.of()), Role.OPERATOR, (a, c) -> credentials.list().stream()
                        .map(g -> Map.of("id", g.id(), "name", g.name())).toList());
    }

    @Bean
    AiTool listAuditLogTool(AuditLogQueryService audit) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("page", Map.of("type", "integer", "minimum", 0));
        props.put("size", Map.of("type", "integer", "minimum", 1, "maximum", 100));
        props.put("action", Map.of("type", "string"));
        props.put("username", Map.of("type", "string"));
        props.put("from", Map.of("type", "string"));
        props.put("to", Map.of("type", "string"));
        return read("list_audit_log", "Audit log, newest first. action e.g. HOST_CREATE; from/to: ISO-8601 instants.",
                schema(props), Role.ADMIN, (a, c) -> {
                    instant(a, "from");
                    instant(a, "to");
                    return audit.find(intArg(a, "page", 0), intArg(a, "size", 20),
                            (String) a.get("action"), (String) a.get("username"), (String) a.get("from"), (String) a.get("to"));
                });
    }

    /** Names and levels only. ADMIN-only: the registry neither offers nor executes it for other roles. */
    @Bean
    AiTool listAccessGrantsTool(PermissionGrantService grants, TeamService teams) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String key : List.of("hostId", "deploymentId", "userId", "teamId")) {
            props.put(key, UUID_PROP);
        }
        return read("list_access_grants", "Access grants; pass exactly one id.",
                schema(props), Role.ADMIN, (a, c) -> {
                    if (props.keySet().stream().filter(a::containsKey).count() != 1) {
                        throw new ToolArgumentException("pass exactly one of hostId, deploymentId, userId, teamId");
                    }
                    if (a.containsKey("hostId")) {
                        return grants.forHost(uuid(a, "hostId"));
                    }
                    if (a.containsKey("deploymentId")) {
                        return grants.forDeployment(uuid(a, "deploymentId"));
                    }
                    if (a.containsKey("userId")) {
                        return grants.get(uuid(a, "userId"));
                    }
                    UUID teamId = uuid(a, "teamId");
                    var team = teams.get(teamId);
                    var teamGrants = grants.getForTeam(teamId);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("name", team.name());
                    out.put("members", team.members());
                    out.put("grants", teamGrants.grants());
                    return out;
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
        // No additionalProperties: ArgumentValidator already rejects unknown arguments, and it costs tokens per tool.
        return required.length == 0 ? Map.of("type", "object", "properties", properties)
                : Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    private static Map<String, Object> hostSchema() {
        return schema(Map.of("hostId", UUID_PROP), "hostId");
    }

    private static Map<String, Object> deploymentSchema() {
        return schema(Map.of("hostId", UUID_PROP, "deploymentId", UUID_PROP), "hostId", "deploymentId");
    }

    private static UUID uuid(Map<String, Object> args, String name) {
        return UUID.fromString((String) args.get(name));
    }

    private static int intArg(Map<String, Object> args, String name, int fallback) {
        return args.get(name) instanceof Number n ? n.intValue() : fallback;
    }
}
