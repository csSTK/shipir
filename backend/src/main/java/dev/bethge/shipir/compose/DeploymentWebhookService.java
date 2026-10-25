package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.token.TokenCodec;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.compose.dto.WebhookIssuedResponse;
import dev.bethge.shipir.compose.dto.WebhookStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The redeploy webhook of a deployment: management (OPERATE permission, shown-once token) and the public trigger.
 * The token is {@code shw_} plus 32 random bytes; only its SHA-256 is stored, the plaintext is returned by
 * {@link #enable} / {@link #rotate} and never logged or audited. Disabled, rotated-away and unknown tokens all give the
 * same 404, so a token's existence cannot be probed.
 */
@Service
public class DeploymentWebhookService {

    static final String TOKEN_PREFIX = "shw_";
    /** Longer input cannot be a token: rejected before hashing. */
    private static final int MAX_TOKEN_LENGTH = 100;
    public static final String TRIGGER_PATH = "/api/webhooks/deploy/";

    private final DeploymentWebhookRepository webhooks;
    private final DeploymentService deployments;
    private final DeploymentRunService runs;
    private final Clock clock;

    DeploymentWebhookService(DeploymentWebhookRepository webhooks, DeploymentService deployments,
                             DeploymentRunService runs, Clock clock) {
        this.webhooks = webhooks;
        this.deployments = deployments;
        this.runs = runs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WebhookStatusResponse status(UUID hostId, UUID id) {
        deployments.requireOperate(hostId, id);
        return webhooks.findByDeploymentId(id)
                .map(w -> new WebhookStatusResponse(w.isEnabled(), w.getCreatedAt(), w.getRotatedAt()))
                .orElse(new WebhookStatusResponse(false, null, null));
    }

    /** Creates the webhook or re-enables a disabled one with a new token. 409 WEBHOOK_ALREADY_ENABLED if it is on. */
    @Transactional
    @Auditable(action = "DEPLOYMENT_WEBHOOK_ENABLE", targetType = "DEPLOYMENT", targetId = "#id")
    public WebhookIssuedResponse enable(UUID hostId, UUID id) {
        deployments.requireOperate(hostId, id);
        String token = TokenCodec.newToken(TOKEN_PREFIX);
        String hash = TokenCodec.sha256Hex(token);
        DeploymentWebhook webhook = webhooks.findByDeploymentId(id).orElse(null);
        if (webhook == null) {
            webhook = webhooks.save(new DeploymentWebhook(id, hash, clock.instant()));
        } else if (webhook.isEnabled()) {
            throw new DeploymentException(HttpStatus.CONFLICT, "WEBHOOK_ALREADY_ENABLED", "The webhook is already enabled");
        } else {
            webhook.issue(hash, clock.instant());
        }
        return issued(webhook, token);
    }

    /** Replaces the token; the old one stops working at once. 404 WEBHOOK_NOT_FOUND unless the webhook is enabled. */
    @Transactional
    @Auditable(action = "DEPLOYMENT_WEBHOOK_ROTATE", targetType = "DEPLOYMENT", targetId = "#id")
    public WebhookIssuedResponse rotate(UUID hostId, UUID id) {
        deployments.requireOperate(hostId, id);
        DeploymentWebhook webhook = enabledOf(id);
        String token = TokenCodec.newToken(TOKEN_PREFIX);
        webhook.issue(TokenCodec.sha256Hex(token), clock.instant());
        return issued(webhook, token);
    }

    /** Turns the webhook off; its token stops working. 404 WEBHOOK_NOT_FOUND unless it is enabled. */
    @Transactional
    @Auditable(action = "DEPLOYMENT_WEBHOOK_DISABLE", targetType = "DEPLOYMENT", targetId = "#id")
    public void disable(UUID hostId, UUID id) {
        deployments.requireOperate(hostId, id);
        enabledOf(id).disable();
    }

    /**
     * Public trigger: pull-update for FILE deployments, git redeploy for GIT deployments, as the system.
     *
     * @throws DeploymentException 404 WEBHOOK_NOT_FOUND for an unknown, rotated-away or disabled token; 409
     *                             DEPLOYMENT_IN_PROGRESS while the deployment is busy
     */
    public DeploymentRunResponse trigger(String token) {
        return runs.webhookTrigger(deploymentOf(token));
    }

    private UUID deploymentOf(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw notFound();
        }
        String hash = TokenCodec.sha256Hex(token);
        return webhooks.findByTokenHash(hash)
                .filter(w -> TokenCodec.hashesEqual(w.getTokenHash(), hash))
                .filter(DeploymentWebhook::isEnabled)
                .map(DeploymentWebhook::getDeploymentId)
                .orElseThrow(DeploymentWebhookService::notFound);
    }

    private DeploymentWebhook enabledOf(UUID deploymentId) {
        return webhooks.findByDeploymentId(deploymentId).filter(DeploymentWebhook::isEnabled)
                .orElseThrow(DeploymentWebhookService::notFound);
    }

    private static WebhookIssuedResponse issued(DeploymentWebhook webhook, String token) {
        return new WebhookIssuedResponse(true, webhook.getCreatedAt(), webhook.getRotatedAt(), token, TRIGGER_PATH + token);
    }

    private static DeploymentException notFound() {
        return new DeploymentException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook not found");
    }
}
