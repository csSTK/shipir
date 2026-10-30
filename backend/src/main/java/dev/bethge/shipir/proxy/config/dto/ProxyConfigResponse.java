package dev.bethge.shipir.proxy.config.dto;

import dev.bethge.shipir.compose.generator.model.ProxyType;

import java.time.Instant;
import java.util.List;

/** The managed (generated, read-only) files and the user's custom file of a host's proxy. */
public record ProxyConfigResponse(ProxyType type, List<ManagedFileView> managed, String customFileName, String custom,
                                  Instant customUpdatedAt) {
}
