package dev.bethge.shipir.compose.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A check with its last state and its most recent results (newest first). */
public record UptimeCheckResponse(UUID id, String name, String url, Integer expectedStatus, int intervalSeconds,
                                  int timeoutSeconds, boolean enabled, String lastStatus, Instant lastCheckedAt,
                                  Instant lastChangeAt, int consecutiveFailures, List<Result> recentResults) {

    public record Result(Instant checkedAt, boolean ok, Integer statusCode, Integer latencyMs, String errorCode) {
    }
}
