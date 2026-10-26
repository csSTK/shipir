package dev.bethge.shipir.compose;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.compose.dto.UptimeCheckRequest;
import dev.bethge.shipir.compose.dto.UptimeCheckResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Management of the HTTP uptime checks of a managed deployment (OPERATE permission) and the recording of probe results.
 * The URL is validated here (http/https, host, no embedded credentials); where it may point is decided at connect time
 * by the shared egress policy, see {@link UptimeProbe}. Audit entries carry the check name only: the URL may contain
 * secrets in its query.
 */
@Service
@EnableConfigurationProperties(UptimeProperties.class)
public class UptimeCheckService {

    static final int MAX_CHECKS_PER_DEPLOYMENT = 10;
    private static final int RECENT_RESULTS = 20;

    private final UptimeCheckRepository checks;
    private final UptimeResultRepository results;
    private final DeploymentService deployments;
    private final ApplicationEventPublisher events;
    private final UptimeProperties properties;
    private final Clock clock;

    UptimeCheckService(UptimeCheckRepository checks, UptimeResultRepository results, DeploymentService deployments,
                       ApplicationEventPublisher events, UptimeProperties properties, Clock clock) {
        this.checks = checks;
        this.results = results;
        this.deployments = deployments;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UptimeCheckResponse> list(UUID hostId, UUID id) {
        deployments.requireOperate(hostId, id);
        deployments.requireDeployment(hostId, id);
        return checks.findByDeploymentIdOrderByNameAsc(id).stream().map(this::toResponse).toList();
    }

    /** 400 INVALID_URL / VALIDATION_ERROR, 409 UPTIME_CHECK_LIMIT (more than 10 checks per deployment). */
    @Transactional
    @Auditable(action = "UPTIME_CHECK_CREATE", targetType = "DEPLOYMENT", targetId = "#id", details = "{'name': #request.name()}")
    public UptimeCheckResponse create(UUID hostId, UUID id, UptimeCheckRequest request) {
        deployments.requireOperate(hostId, id);
        deployments.requireDeployment(hostId, id);
        String url = validUrl(request.url());
        if (checks.countByDeploymentId(id) >= MAX_CHECKS_PER_DEPLOYMENT) {
            throw new DeploymentException(HttpStatus.CONFLICT, "UPTIME_CHECK_LIMIT",
                    "At most " + MAX_CHECKS_PER_DEPLOYMENT + " uptime checks per deployment");
        }
        UptimeCheck check = checks.save(new UptimeCheck(id, request.name().strip(), url, request.expectedStatus(),
                request.intervalSeconds(), request.timeoutSeconds(), request.enabled() == null || request.enabled(),
                clock.instant()));
        return toResponse(check);
    }

    @Transactional
    @Auditable(action = "UPTIME_CHECK_UPDATE", targetType = "DEPLOYMENT", targetId = "#id", details = "{'name': #request.name()}")
    public UptimeCheckResponse update(UUID hostId, UUID id, UUID checkId, UptimeCheckRequest request) {
        deployments.requireOperate(hostId, id);
        deployments.requireDeployment(hostId, id);
        UptimeCheck check = require(id, checkId);
        String url = validUrl(request.url());
        check.update(request.name().strip(), url, request.expectedStatus(), request.intervalSeconds(),
                request.timeoutSeconds(), request.enabled() == null ? check.isEnabled() : request.enabled());
        return toResponse(check);
    }

    @Transactional
    @Auditable(action = "UPTIME_CHECK_DELETE", targetType = "DEPLOYMENT", targetId = "#id")
    public void delete(UUID hostId, UUID id, UUID checkId) {
        deployments.requireOperate(hostId, id);
        deployments.requireDeployment(hostId, id);
        checks.delete(require(id, checkId));
    }

    /**
     * Stores one probe result and applies the state machine; publishes {@link UptimeStateChangedEvent} on DOWN (after the
     * configured consecutive failures) and on recovery. A check deleted meanwhile is ignored.
     */
    @Transactional
    void record(UUID checkId, UptimeProbe.ProbeResult result, Instant at) {
        UptimeCheck check = checks.findById(checkId).orElse(null);
        if (check == null) {
            return;
        }
        results.save(new UptimeResult(checkId, at, result.ok(), result.statusCode(), result.latencyMs(), result.errorCode()));
        UptimeCheck.State changed = check.apply(result.ok(), at, properties.failureThreshold());
        if (changed == null) {
            return;
        }
        Deployment deployment = deployments.requireDeployment(check.getDeploymentId());
        events.publishEvent(new UptimeStateChangedEvent(deployment.getHostId(), hostName(deployment.getHostId()),
                deployment.getId(), deployment.getName(), check.getName(), changed == UptimeCheck.State.DOWN,
                changed == UptimeCheck.State.DOWN ? publicErrorCode(result.errorCode()) : null, at));
    }

    /**
     * Notifications and webhooks reach more people than the check's OPERATORs, so a policy refusal is reported as a plain
     * connect failure: it must not tell a recipient that an internal target was blocked. The stored result keeps the code.
     */
    private static String publicErrorCode(String code) {
        return "EGRESS_DENIED".equals(code) ? "CONNECTION_FAILED" : code;
    }

    @Transactional
    int purgeResultsOlderThan(Instant before) {
        return results.deleteOlderThan(before);
    }

    private String hostName(UUID hostId) {
        try {
            return deployments.requireHost(hostId).getName();
        } catch (RuntimeException e) {
            return hostId.toString();
        }
    }

    private UptimeCheck require(UUID deploymentId, UUID checkId) {
        return checks.findById(checkId).filter(c -> c.getDeploymentId().equals(deploymentId)).orElseThrow(
                () -> new DeploymentException(HttpStatus.NOT_FOUND, "UPTIME_CHECK_NOT_FOUND", "Uptime check not found"));
    }

    private static String validUrl(String raw) {
        String url = raw == null ? "" : raw.strip();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw invalid("The URL must start with http:// or https://");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalid("The URL needs a host");
            }
            if (uri.getRawUserInfo() != null) {
                throw invalid("The URL must not contain credentials");
            }
            return url;
        } catch (URISyntaxException e) {
            throw invalid("The URL is not valid");
        }
    }

    private static DeploymentException invalid(String message) {
        return new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_URL", message);
    }

    private UptimeCheckResponse toResponse(UptimeCheck c) {
        List<UptimeCheckResponse.Result> recent = results.latest(c.getId(), PageRequest.of(0, RECENT_RESULTS)).stream()
                .map(r -> new UptimeCheckResponse.Result(r.getCheckedAt(), r.isOk(), r.getStatusCode(), r.getLatencyMs(),
                        r.getErrorCode()))
                .toList();
        return new UptimeCheckResponse(c.getId(), c.getName(), c.getUrl(), c.getExpectedStatus(), c.getIntervalSeconds(),
                c.getTimeoutSeconds(), c.isEnabled(), c.getLastStatus().name(), c.getLastCheckedAt(), c.getLastChangeAt(),
                c.getConsecutiveFailures(), recent);
    }
}
