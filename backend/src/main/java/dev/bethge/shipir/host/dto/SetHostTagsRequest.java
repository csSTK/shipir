package dev.bethge.shipir.host.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Replaces the whole tag set; an empty list removes all tags. Names are checked by {@code HostTagPolicy}. */
public record SetHostTagsRequest(@NotNull List<String> tags) {
}
