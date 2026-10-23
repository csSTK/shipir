package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.compose.EnvFile;
import dev.bethge.shipir.compose.DeploymentRunService;
import dev.bethge.shipir.compose.DeploymentService;
import dev.bethge.shipir.compose.dto.AutoUpdateRequest;
import dev.bethge.shipir.compose.dto.EnvEntry;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentUpdateRequest;
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

    private static final Map<String, Object> UUID_PROP = Map.of("type", "string", "format", "uuid");
    private static final Map<String, Object> OK = Map.of("status", "ok");

    private final ObjectMapper mapper;

    WriteToolsConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- deployments (ADMIN and OPERATOR) ----

    @Bean
    AiTool deployDeploymentTool(DeploymentRunService deployments) {
        return write("run_deployment", "Deploy (docker compose up) the current draft of a managed deployment. Runs asynchronously; returns a run id.",
                deploymentSchema(), Role.OPERATOR, (a, c) -> deployments.deploy(uuid(a, "hostId"), uuid(a, "deploymentId"), c.username()));
    }

    @Bean
    AiTool stopDeploymentTool(DeploymentRunService deployments) {
        return write("stop_deployment", "Stop a managed deployment (docker compose down).",
                deploymentSchema(), Role.OPERATOR, (a, c) -> deployments.stop(uuid(a, "hostId"), uuid(a, "deploymentId")));
    }

    @Bean
    AiTool rollbackDeploymentTool(DeploymentRunService deployments) {
        return write("rollback_deployment", "Redeploy an earlier version of a managed deployment (see list_deployment_versions). Runs asynchronously.",
                schema(Map.of("hostId", UUID_PROP, "deploymentId", UUID_PROP, "versionId", UUID_PROP), "hostId", "deploymentId", "versionId"),
                Role.OPERATOR, (a, c) -> deployments.rollback(uuid(a, "hostId"), uuid(a, "deploymentId"), uuid(a, "versionId"), c.username()));
    }

    @Bean
    AiTool gitRedeployDeploymentTool(DeploymentRunService deployments) {
        return write("git_redeploy_deployment", "Pull the latest commit of a Git-based deployment and redeploy it. Runs asynchronously.",
                deploymentSchema(), Role.OPERATOR, (a, c) -> deployments.gitRedeploy(uuid(a, "hostId"), uuid(a, "deploymentId"), c.username()));
    }

    @Bean
    AiTool createDeploymentTool(DeploymentService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("name", Map.of("type", "string", "description", "Lowercase letters, digits, '-' and '_' (max 63)."));
        props.put("yaml", Map.of("type", "string", "description", "Compose file content."));
        props.put("env", Map.of("type", "string", "description", ENV_DESCRIPTION));
        return write("create_deployment", "Create a managed deployment from Compose YAML (not deployed yet; use run_deployment afterwards).",
                schema(props, "hostId", "name", "yaml"), Role.OPERATOR, (a, c) -> deployments.create(uuid(a, "hostId"),
                        new DeploymentCreateRequest((String) a.get("name"), (String) a.get("yaml"), env(a)), c.username()));
    }

    @Bean
    AiTool updateDeploymentTool(DeploymentService deployments) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("hostId", UUID_PROP);
        props.put("deploymentId", UUID_PROP);
        props.put("yaml", Map.of("type", "string", "description", "New Compose file content."));
        props.put("env", Map.of("type", "string", "description", ENV_DESCRIPTION + " Omit to keep the stored variables."));
        return write("update_deployment", "Save new Compose YAML (and optionally variables) as a draft of a managed deployment; run_deployment applies it.",
                schema(props, "hostId", "deploymentId", "yaml"), Role.OPERATOR, (a, c) -> deployments.update(uuid(a, "hostId"),
                        uuid(a, "deploymentId"), new DeploymentUpdateRequest((String) a.get("yaml"), env(a)), c.username()));
    }

    @Bean
    AiTool deleteDeploymentTool(DeploymentService deployments) {
        return write("delete_deployment", "Delete a managed deployment: runs docker compose down and removes it and its version history from Shipir.",
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
        props.put("intervalSeconds", Map.of("type", "integer", "minimum", 60, "description", "Check interval, at least 60."));
        return write("set_auto_update", "Turn automatic redeploys on or off for a Git-based deployment.",
                schema(props, "hostId", "deploymentId", "enabled"), Role.OPERATOR, (a, c) -> deployments.configureAutoUpdate(
                        uuid(a, "hostId"), uuid(a, "deploymentId"), new AutoUpdateRequest((Boolean) a.get("enabled"),
                                a.get("intervalSeconds") instanceof Number n ? n.intValue() : null)));
    }

    @Bean
    AiTool markNotificationsReadTool(NotificationService notifications) {
        Map<String, Object> props = Map.of("notificationId", Map.of("type", "string", "format", "uuid",
                "description", "One notification; omit to mark all of the current user's notifications read."));
        return write("mark_notifications_read", "Mark one or all of the current user's notifications as read.",
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
        return write("create_host", "Add the local Docker daemon of the Shipir server as an environment. Remote (TCP/TLS) environments need certificates and must be added in the UI.",
                schema(Map.of("name", Map.of("type", "string", "description", "Display name (max 100).")), "name"), Role.ADMIN,
                (a, c) -> hosts.create(new CreateHostRequest((String) a.get("name"), ConnectionType.LOCAL, null, null,
                        null, null, null), c.username()));
    }

    @Bean
    AiTool renameHostTool(HostService hosts) {
        return write("rename_host", "Rename an environment.",
                schema(Map.of("hostId", UUID_PROP, "name", Map.of("type", "string", "description", "New name (max 100).")), "hostId", "name"),
                Role.ADMIN, (a, c) -> hosts.rename(uuid(a, "hostId"), (String) a.get("name")));
    }

    @Bean
    AiTool deleteHostTool(HostService hosts) {
        return write("delete_host", "Delete an environment from Shipir, including its managed deployments' records (containers on the host are not touched).",
                hostSchema(), Role.ADMIN, (a, c) -> {
                    hosts.delete(uuid(a, "hostId"));
                    return OK;
                });
    }

    @Bean
    AiTool setWebhookEnabledTool(WebhookService webhooks) {
        return write("set_webhook_enabled", "Enable or disable a notification webhook. Its URL cannot be changed here.",
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
        return write("delete_git_credential", "Delete a Git credential that no deployment uses.",
                schema(Map.of("credentialId", UUID_PROP), "credentialId"), Role.ADMIN, (a, c) -> {
                    credentials.delete(uuid(a, "credentialId"));
                    return OK;
                });
    }

    private static final String ENV_DESCRIPTION = "Variables as .env text, one KEY=VALUE per line. Values are stored encrypted and never shown again.";

    private static List<EnvEntry> env(Map<String, Object> args) {
        return args.get("env") instanceof String text ? EnvFile.parse(text) : null;
    }

    private AiTool write(String name, String description, Map<String, Object> parameters, Role role,
                         BiFunction<Map<String, Object>, ToolContext, Object> handler) {
        return new FunctionTool(name, description, parameters, role, true, handler, mapper::writeValueAsString);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required),
                "additionalProperties", false);
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
}
