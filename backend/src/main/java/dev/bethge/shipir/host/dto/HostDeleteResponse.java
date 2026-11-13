package dev.bethge.shipir.host.dto;

import java.util.List;

/** Result of deleting an environment. {@code leftovers} names what a requested proxy removal could not remove. */
public record HostDeleteResponse(List<String> leftovers) {
}
