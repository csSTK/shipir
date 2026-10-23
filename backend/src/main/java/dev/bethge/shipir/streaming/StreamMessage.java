package dev.bethge.shipir.streaming;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON frame sent to subscribers of {@code /topic/deployments/{id}}. {@code seq} is strictly increasing per
 * deployment (clients can detect gaps); the last frame has {@code type=STATUS}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamMessage(long seq, Type type, String line, DeploymentStatus status) {

    public enum Type { LINE, STATUS }
}
