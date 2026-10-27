package dev.bethge.shipir.permission.dto;

import dev.bethge.shipir.permission.PermissionLevel;

import java.util.UUID;

/** One grant on a host or deployment, seen from the target: {@code subjectType} is USER or TEAM. */
public record SubjectGrantResponse(String subjectType, UUID subjectId, String subjectName, PermissionLevel level) {
}
