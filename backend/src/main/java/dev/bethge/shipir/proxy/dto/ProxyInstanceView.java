package dev.bethge.shipir.proxy.dto;

import dev.bethge.shipir.compose.DeploymentStatus;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.CertMode;

import java.util.UUID;

/**
 * The managed proxy of a host. DNS credentials are never part of it, only {@code dnsCredentialsHint} (null when none
 * are stored). {@code deploymentStatus} is null when the proxy deployment no longer exists.
 */
public record ProxyInstanceView(ProxyType type, String acmeEmail, CertMode defaultCertMode, String dnsProvider,
                                String dnsCredentialsHint, UUID deploymentId, DeploymentStatus deploymentStatus) {
}
