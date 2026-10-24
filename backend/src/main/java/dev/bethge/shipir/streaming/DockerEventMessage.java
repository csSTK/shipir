package dev.bethge.shipir.streaming;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.bethge.shipir.docker.dto.DockerEventDto;

import java.util.List;

/**
 * Frame of {@code /topic/hosts/{hostId}/events}. EVENT carries a debounced batch {@code events} (distinct by
 * type, action and id) and {@code omitted} (count of further distinct events cut by the per-frame cap, only
 * when greater than 0). STATUS carries {@code status}: CONNECTED (stream to the daemon is open, also after a
 * reconnect, events may have been missed meanwhile) or DISCONNECTED with a stable {@code code}
 * (UNREACHABLE, TIMEOUT, TLS_ERROR, PERMISSION_DENIED, INVALID_TLS_MATERIAL, NOT_FOUND = host gone,
 * UNAVAILABLE); the server keeps retrying with backoff except for NOT_FOUND. A subscriber that joins later
 * first receives the latest STATUS frame. {@code seq} counts frames per host.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DockerEventMessage(long seq, Type type, List<DockerEventDto> events, Integer omitted, Status status,
                                 String code) {

    public enum Type { EVENT, STATUS }

    public enum Status { CONNECTED, DISCONNECTED }
}
