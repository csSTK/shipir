package dev.bethge.shipir.compose.dto;

import java.util.List;

/** Service and profile names used by the deployment's compose file (including services behind a profile), sorted. */
public record ServicesResponse(List<String> services, List<String> profiles) {
}
