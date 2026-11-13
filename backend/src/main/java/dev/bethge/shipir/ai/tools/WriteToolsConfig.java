package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.DeploymentException;
import dev.bethge.shipir.compose.DeploymentRunService;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.DeploymentSource;
import dev.bethge.shipir.compose.EnvFile;
import dev.bethge.shipir.compose.dto.AutoUpdateRequest;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentUpdateRequest;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.dto.ProfilesRequest;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.git.GitCredentialService;
import dev.bethge.shipir.host.ConnectionType;
import dev.bethge.shipir.host.HostService;
import dev.bethge.shipir.host.dto.CreateHostRequest;
import dev.bethge.shipir.notification.NotificationService;
import dev.bethge.shipir.notification.WebhookService;
import dev.bethge.shipir.notification.dto.WebhookRequest;
import dev.bethge.shipir.notification.dto.WebhookResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static dev.bethge.shipir.ai.tools.ToolSchemas.UUID_PROP;
import static dev.bethge.shipir.ai.tools.ToolSchemas.deploymentSchema;
import static dev.bethge.shipir.ai.tools.ToolSchemas.hostSchema;
import static dev.bethge.shipir.ai.tools.ToolSchemas.schema;

/**
 * The state-changing tools ({@code write = true}), so {@link AiToolRegistry} only runs them after the user confirmed.
 * Each one calls the same service method as the REST endpoint and needs at least the role that endpoint needs, except
 * {@code mark_notifications_read}, which is OPERATOR here so a VIEWER never sees a write tool. Secrets never travel
 * through the chat: only LOCAL environments can be created, webhook and Git credential tools cannot set URLs or
 * tokens, and the deployment {@code env} argument is masked in the confirmation view. The acting user is
 * {@link ToolContext#username()}, never an argument.
 */
@Configuration(proxyBeanMethods = false)
class WriteToolsConfig {

    private static final Map<String, Object> OK = Map.of("status", "ok");

    private final ObjectMapper mapper;

    WriteToolsConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- deployments (ADMIN and OPERATOR) ----

    @Bean
    AiTool deployDeploymentTool(DeploymentRunService deployments) {
        return write("run_deployment", "Deploy the current draft of a managed deployment (async, returns run id).",
                deploymentSchema(), Role.OPERATOR, (a, c) -> deployments.deploy(uuid(a, "hostId"), uuid(a, "deploymentId"), c.username()));
    }

    @Bean
    AiTool stopDeploymentTool(DeploymentRunService deployments) {
        return write("stop_deployment", "Stop a managed deployment.",
                deploymentSchema(), Role.OPERATOR, (a, c) -> deployments.stop(uuid(a, "hostId"), uuid(a, "deploymentId")));
    }

    @Bean
    AiTool rollbackDeploymentTool(DeploymentRunService deployments) {
        return write("rollback_deployment", "Redeploy an earlier version (see list_deployment_versions). Async.",
                schema(Map.of("hostId", UUID_PROP, "deploymentId", UUID_PROP, "versionId", UUID_PROP), "hostId", "deploymentId", "versionId"),
                Role.OPERATOR, (a, c) -> deployments.rollback(uuid(a, "hostId"), uuid(a, "deploymentId"), uuid(a, "versionId"), c.username()));
    }

    @Bean
    AiTool gitRedeployDeploymentTool(DeploymentRunService deployments) {
        return write("git_redeploy_deployment", "Pull the latest commit of a Git deployment and redeploy. Async. Not for ADMINs.",
                deploymentSchema(), Role.OPERATOR, (a, c) -> {
                    try {
                        return deployments.gitRedeploy(uuid(a, "hostId"), uuid(a, "deploymentId"), c.username());
                    } catch (DeploymentException e) {
                        if ("REVIEW_REQUIRED".equals(e.code())) {
                            // never bypass the review: the answer only has to be readable by the model
                            throw new DeploymentException(e.status(), e.code(), "As ADMIN, Git redeploys need a review of the pulled "
                                    + "files in the UI (Deployment > Pull & redeploy); this can't be done from chat.");
                        }
                        throw e;
                    }
                });
    }

    @Bean
    AiTool createDeploymentTool(DeploymentService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("name", Map.of("type", "string"));
        props.put("source", Map.of("type", "string", "enum", List.of("FILE", "GIT"),
                "description", "Default FILE. GIT whenever a repository is named."));
        props.put("yaml", Map.of("type", "string", "description", "Compose YAML (FILE)."));
        props.put("repoUrl", Map.of("type", "string", "description", "GIT only."));
        props.put("ref", Map.of("type", "string", "description", "Branch, tag or SHA (GIT only)."));
        props.put("composePath", Map.of("type", "string", "description", "Compose file path in repo (GIT only)."));
        props.put("credentialId", Map.of("type", "string", "format", "uuid",
                "description", "Id from list_git_credentials, private repos only."));
        props.put("env", Map.of("type", "string", "description", ENV_DESCRIPTION));
        return write("create_deployment", "Create a managed deployment from YAML or a Git repo (name: a-z, 0-9, -, _; GIT needs repoUrl, ref, composePath). Not deployed; use run_deployment.",
                schema(props, "hostId", "name"), Role.OPERATOR, (a, c) -> deployments.create(uuid(a, "hostId"),
                        new DeploymentCreateRequest((String) a.get("name"), (String) a.get("yaml"), env(a),
                                "GIT".equals(a.get("source")) ? DeploymentSource.GIT : DeploymentSource.FILE,
                                (String) a.get("repoUrl"), (String) a.get("ref"), (String) a.get("composePath"),
                                a.get("credentialId") == null ? null : uuid(a, "credentialId")), c.username()));
    }

    @Bean
    AiTool updateDeploymentTool(DeploymentService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("deploymentId", UUID_PROP);
        props.put("yaml", Map.of("type", "string"));
        props.put("env", Map.of("type", "string", "description", ENV_DESCRIPTION + " Omit to keep."));
        return write("update_deployment", "Save new Compose YAML as a draft; run_deployment applies it.",
                schema(props, "hostId", "deploymentId", "yaml"), Role.OPERATOR, (a, c) -> deployments.update(uuid(a, "hostId"),
                        uuid(a, "deploymentId"), new DeploymentUpdateRequest((String) a.get("yaml"), env(a)), c.username()));
    }

    @Bean
    AiTool deleteDeploymentTool(DeploymentService deployments) {
        return write("delete_deployment", "Delete a managed deployment (compose down) and its history.",
                deploymentSchema(), Role.OPERATOR, (a, c) -> {
                    deployments.delete(uuid(a, "hostId"), uuid(a, "deploymentId"));
                    return OK;
                });
    }

    @Bean
    AiTool setAutoUpdateTool(DeploymentService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("deploymentId", UUID_PROP);
        props.put("enabled", Map.of("type", "boolean"));
        props.put("intervalSeconds", Map.of("type", "integer", "minimum", 60));
        return write("set_auto_update", "Set auto-redeploy for a Git deployment.",
                schema(props, "hostId", "deploymentId", "enabled"), Role.OPERATOR, (a, c) -> deployments.configureAutoUpdate(
                        uuid(a, "hostId"), uuid(a, "deploymentId"), new AutoUpdateRequest((Boolean) a.get("enabled"),
                                a.get("intervalSeconds") instanceof Number n ? n.intValue() : null)));
    }

    @Bean
    AiTool serviceActionTool(DeploymentRunService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("deploymentId", UUID_PROP);
        props.put("service", Map.of("type", "string"));
        props.put("action", Map.of("type", "string", "enum", List.of("restart", "pull", "up")));
        return write("service_action", "Restart, pull or up one service (names: list_deployment_services). Async.",
                schema(props, "hostId", "deploymentId", "service", "action"), Role.OPERATOR, (a, c) -> {
                    UUID host = uuid(a, "hostId");
                    UUID id = uuid(a, "deploymentId");
                    String service = (String) a.get("service");
                    return switch ((String) a.get("action")) {
                        case "restart" -> deployments.restartService(host, id, service, c.username());
                        case "pull" -> deployments.pullService(host, id, service, c.username());
                        default -> deployments.upService(host, id, service, c.username());
                    };
                });
    }

    @Bean
    AiTool setDeploymentProfilesTool(DeploymentService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("deploymentId", UUID_PROP);
        props.put("profiles", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 50));
        return write("set_deployment_profiles", "Set Compose profiles ([] = none) for the next runs.",
                schema(props, "hostId", "deploymentId", "profiles"), Role.OPERATOR, (a, c) -> deployments.configureProfiles(
                        uuid(a, "hostId"), uuid(a, "deploymentId"), new ProfilesRequest(stringList(a.get("profiles")))));
    }

    @Bean
    AiTool markNotificationsReadTool(NotificationService notifications) {
        Map<String, Object> props = Map.of("notificationId", Map.of("type", "string", "format", "uuid",
                "description", "Omit for all."));
        return write("mark_notifications_read", "Mark one or all own notifications read.",
                schema(props), Role.OPERATOR, (a, c) -> {
                    if (a.get("notificationId") == null) {
                        notifications.markAllRead(c.userId());
                    } else if (!notifications.markRead(uuid(a, "notificationId"), c.userId())) {
                        throw new ToolArgumentException("No such notification");
                    }
                    return OK;
                });
    }

    // ---- environments, webhooks, Git credentials (ADMIN) ----

    @Bean
    AiTool createHostTool(HostService hosts) {
        return write("create_host", "Add the server's local Docker daemon as an environment.",
                schema(Map.of("name", Map.of("type", "string")), "name"), Role.ADMIN,
                (a, c) -> hosts.create(new CreateHostRequest((String) a.get("name"), ConnectionType.LOCAL, null, null,
                        null, null, null), c.username()));
    }

    @Bean
    AiTool renameHostTool(HostService hosts) {
        return write("rename_host", "Rename an environment.",
                schema(Map.of("hostId", UUID_PROP, "name", Map.of("type", "string")), "hostId", "name"),
                Role.ADMIN, (a, c) -> hosts.rename(uuid(a, "hostId"), (String) a.get("name")));
    }

    @Bean
    AiTool deleteHostTool(HostService hosts) {
        return write("delete_host", "Delete an environment and its deployment records (host containers untouched).",
                hostSchema(), Role.ADMIN, (a, c) -> {
                    hosts.delete(uuid(a, "hostId"), false);
                    return OK;
                });
    }

    @Bean
    AiTool setWebhookEnabledTool(WebhookService webhooks) {
        return write("set_webhook_enabled", "Enable or disable a webhook (URL cannot be changed here).",
                schema(Map.of("webhookId", UUID_PROP, "enabled", Map.of("type", "boolean")), "webhookId", "enabled"), Role.ADMIN, (a, c) -> {
                    UUID id = uuid(a, "webhookId");
                    WebhookResponse current = webhooks.list().stream().filter(w -> w.id().equals(id)).findFirst()
                            .orElseThrow(() -> new ToolArgumentException("No such webhook"));
                    return webhooks.update(id, new WebhookRequest(current.name(), null, current.eventTypes(),
                            (Boolean) a.get("enabled")));
                });
    }

    @Bean
    AiTool deleteWebhookTool(WebhookService webhooks) {
        return write("delete_webhook", "Delete a notification webhook.",
                schema(Map.of("webhookId", UUID_PROP), "webhookId"), Role.ADMIN, (a, c) -> {
                    webhooks.delete(uuid(a, "webhookId"));
                    return OK;
                });
    }

    @Bean
    AiTool deleteGitCredentialTool(GitCredentialService credentials) {
        return write("delete_git_credential", "Delete an unused Git credential.",
                schema(Map.of("credentialId", UUID_PROP), "credentialId"), Role.ADMIN, (a, c) -> {
                    credentials.delete(uuid(a, "credentialId"));
                    return OK;
                });
    }

    private static final String ENV_DESCRIPTION = ".env text, KEY=VALUE lines.";

    private static List<EnvEntry> env(Map<String, Object> args) {
        return args.get("env") instanceof String text ? EnvFile.parse(text) : null;
    }

    private AiTool write(String name, String description, Map<String, Object> parameters, Role role,
                         BiFunction<Map<String, Object>, ToolContext, Object> handler) {
        return new FunctionTool(name, description, parameters, role, true, handler, mapper::writeValueAsString);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new ToolArgumentException("profiles must be a list of strings");
        }
        List<String> names = list.stream().map(String.class::cast).toList();
        // the REST endpoint validates the same rule with bean validation; the tool path has no @Valid
        if (!names.stream().allMatch(n -> ComposeOptions.NAME.matcher(n).matches())) {
            throw new ToolArgumentException("profile names may only use letters, digits, '_', '.' and '-'");
        }
        return names;
    }

    private static UUID uuid(Map<String, Object> args, String name) {
        return UUID.fromString((String) args.get(name));
    }
}
