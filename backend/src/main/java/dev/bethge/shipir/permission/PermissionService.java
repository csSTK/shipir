package dev.bethge.shipir.permission;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.DockerHost;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Access decisions over hosts and managed deployments.
 * <ul>
 *   <li>ADMIN: everything.</li>
 *   <li>Others: only granted targets. A host grant covers all deployments of the host; a deployment grant covers
 *       only that deployment (the host is then visible, but only with {@link #visibleDeploymentIds}).</li>
 *   <li>A grant allows at most what the global role allows: VIEWER never operates, even with an OPERATE grant.</li>
 *   <li>For a deployment the better of its host grant and its own grant applies.</li>
 *   <li>Grants of the user's teams count like own grants; the best level over all of them applies.</li>
 * </ul>
 * Unmanaged (discovered) deployments have no id; they are visible exactly when {@link #hostFullyVisible}.
 */
@Service
public class PermissionService {

    private final PermissionRepository grants;
    private final DeploymentDirectory deployments;
    private final DockerHostRepository hosts;

    PermissionService(PermissionRepository grants, DeploymentDirectory deployments, DockerHostRepository hosts) {
        this.grants = grants;
        this.deployments = deployments;
        this.hosts = hosts;
    }

    /** The host is visible at all: host grant or a grant on one of its deployments. */
    @Transactional(readOnly = true)
    public boolean canView(PermissionSubject subject, UUID hostId) {
        if (isAdmin(subject)) {
            return true;
        }
        return hostLevel(subject, hostId).isPresent() || hasDeploymentGrantOn(subject, hostId);
    }

    /** Host-level actions (e.g. create deployment, container actions): needs an OPERATE host grant. */
    @Transactional(readOnly = true)
    public boolean canOperate(PermissionSubject subject, UUID hostId) {
        if (isAdmin(subject)) {
            return true;
        }
        return allowsOperate(subject, hostLevel(subject, hostId));
    }

    @Transactional(readOnly = true)
    public boolean canView(PermissionSubject subject, UUID hostId, UUID deploymentId) {
        return isAdmin(subject) || deploymentLevel(subject, hostId, deploymentId).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean canOperate(PermissionSubject subject, UUID hostId, UUID deploymentId) {
        return isAdmin(subject) || allowsOperate(subject, deploymentLevel(subject, hostId, deploymentId));
    }

    /** True when the subject may OPERATE at least one managed deployment of the host (via any grant). */
    @Transactional(readOnly = true)
    public boolean canOperateAnyDeployment(PermissionSubject subject, UUID hostId) {
        if (isAdmin(subject)) {
            return true;
        }
        return deployments.deploymentIdsOf(hostId).stream().anyMatch(d -> canOperate(subject, hostId, d));
    }

    /** True when the whole host (all deployments, unmanaged ones, containers, ...) is visible. */
    @Transactional(readOnly = true)
    public boolean hostFullyVisible(PermissionSubject subject, UUID hostId) {
        return isAdmin(subject) || hostLevel(subject, hostId).isPresent();
    }

    /** Hosts the subject can see, fully or only through a deployment grant. */
    @Transactional(readOnly = true)
    public Set<UUID> visibleHostIds(PermissionSubject subject) {
        if (isAdmin(subject)) {
            return new HashSet<>(hosts.findAll().stream().map(DockerHost::getId).toList());
        }
        Set<UUID> ids = new HashSet<>();
        for (Permission p : grants.findEffectiveByUser(subject.userId())) {
            if (p.getHostId() != null) {
                ids.add(p.getHostId());
            } else {
                deployments.hostIdOf(p.getDeploymentId()).ifPresent(ids::add);
            }
        }
        return ids;
    }

    /** Managed deployments of the host the subject can see: all of them if the host is fully visible. */
    @Transactional(readOnly = true)
    public Set<UUID> visibleDeploymentIds(PermissionSubject subject, UUID hostId) {
        if (hostFullyVisible(subject, hostId)) {
            return deployments.deploymentIdsOf(hostId);
        }
        Set<UUID> onHost = deployments.deploymentIdsOf(hostId);
        Set<UUID> ids = new HashSet<>();
        for (Permission p : grants.findEffectiveByUser(subject.userId())) {
            if (p.getDeploymentId() != null && onHost.contains(p.getDeploymentId())) {
                ids.add(p.getDeploymentId());
            }
        }
        return ids;
    }

    private boolean isAdmin(PermissionSubject subject) {
        return subject.role() == Role.ADMIN;
    }

    private Optional<PermissionLevel> hostLevel(PermissionSubject subject, UUID hostId) {
        return best(grants.findEffectiveByUserAndHost(subject.userId(), hostId));
    }

    private boolean hasDeploymentGrantOn(PermissionSubject subject, UUID hostId) {
        List<Permission> mine = grants.findEffectiveByUser(subject.userId());
        Set<UUID> onHost = null;
        for (Permission p : mine) {
            if (p.getDeploymentId() != null) {
                if (onHost == null) {
                    onHost = deployments.deploymentIdsOf(hostId);
                }
                if (onHost.contains(p.getDeploymentId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Best level over host grant and deployment grant; the deployment must belong to {@code hostId}. */
    private Optional<PermissionLevel> deploymentLevel(PermissionSubject subject, UUID hostId, UUID deploymentId) {
        Optional<UUID> owner = deployments.hostIdOf(deploymentId);
        if (owner.isEmpty() || !owner.get().equals(hostId)) {
            return Optional.empty();
        }
        Optional<PermissionLevel> host = hostLevel(subject, hostId);
        Optional<PermissionLevel> own = best(grants.findEffectiveByUserAndDeployment(subject.userId(), deploymentId));
        if (host.isEmpty()) {
            return own;
        }
        if (own.isEmpty()) {
            return host;
        }
        return Optional.of(host.get() == PermissionLevel.OPERATE || own.get() == PermissionLevel.OPERATE
                ? PermissionLevel.OPERATE : PermissionLevel.VIEW);
    }

    /** Best level over the user's own grant and the grants of all their teams. */
    private Optional<PermissionLevel> best(List<Permission> matching) {
        return matching.stream().map(Permission::getLevel)
                .reduce((a, b) -> a == PermissionLevel.OPERATE || b == PermissionLevel.OPERATE
                        ? PermissionLevel.OPERATE : PermissionLevel.VIEW);
    }

    private boolean allowsOperate(PermissionSubject subject, Optional<PermissionLevel> level) {
        return subject.role() == Role.OPERATOR && level.filter(l -> l == PermissionLevel.OPERATE).isPresent();
    }
}
