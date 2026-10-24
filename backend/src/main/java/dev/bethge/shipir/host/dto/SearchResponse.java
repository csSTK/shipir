package dev.bethge.shipir.host.dto;

import java.util.List;

/** {@code results}: hosts, then deployments, then containers; {@code truncated}: more hits than the limit. */
public record SearchResponse(List<SearchResult> results, boolean truncated) {
}
