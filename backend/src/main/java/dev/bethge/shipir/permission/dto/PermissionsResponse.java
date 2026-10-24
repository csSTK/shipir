package dev.bethge.shipir.permission.dto;

import java.util.List;

public record PermissionsResponse(List<GrantResponse> grants) {
}
