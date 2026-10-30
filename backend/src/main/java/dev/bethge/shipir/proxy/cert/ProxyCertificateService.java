package dev.bethge.shipir.proxy.cert;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.permission.CurrentAccess;
import dev.bethge.shipir.proxy.ProxyCertificate;
import dev.bethge.shipir.proxy.ProxyCertificateRepository;
import dev.bethge.shipir.proxy.ProxyException;
import dev.bethge.shipir.proxy.ProxyHostAccess;
import dev.bethge.shipir.proxy.ProxyInstanceRepository;
import dev.bethge.shipir.proxy.ProxyRoute;
import dev.bethge.shipir.proxy.ProxyRouteRepository;
import dev.bethge.shipir.proxy.ProxyRouteValidator;
import dev.bethge.shipir.proxy.ProxySyncService;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.proxy.cert.dto.ProxyCertificateRenewRequest;
import dev.bethge.shipir.proxy.cert.dto.ProxyCertificateRequest;
import dev.bethge.shipir.proxy.cert.dto.ProxyCertificateView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Bring-your-own certificates of a host's managed proxy. Reading is open to every role that sees the host; writes
 * need ADMIN and OPERATE on the host. The key is checked, encrypted with {@link SecretCipher} and never returned,
 * logged or audited. Writes are serialized with the host's other proxy changes; renewing and deleting touch the
 * proxy container too (re-sync / removal of the key files) and roll the database change back when that fails.
 */
@Service
public class ProxyCertificateService {

    private static final Logger log = LoggerFactory.getLogger(ProxyCertificateService.class);

    private final ProxyCertificateRepository certificates;
    private final ProxyRouteRepository routes;
    private final ProxyInstanceRepository instances;
    private final ProxyHostAccess access;
    private final ProxySyncService sync;
    private final SecretCipher cipher;
    private final Clock clock;
    private final CurrentAccess currentAccess;
    private final TransactionTemplate tx;

    ProxyCertificateService(ProxyCertificateRepository certificates, ProxyRouteRepository routes,
                            ProxyInstanceRepository instances, ProxyHostAccess access, ProxySyncService sync,
                            SecretCipher cipher, Clock clock, CurrentAccess currentAccess,
                            PlatformTransactionManager transactions) {
        this.certificates = certificates;
        this.routes = routes;
        this.instances = instances;
        this.access = access;
        this.sync = sync;
        this.cipher = cipher;
        this.clock = clock;
        this.currentAccess = currentAccess;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Certificates are host-wide, not tied to a deployment, so only users who see the whole host get them; with
     * deployment grants only the list is empty (the route list shows those users just their own containers' routes).
     */
    @Transactional(readOnly = true)
    public List<ProxyCertificateView> list(UUID hostId) {
        access.requireView(hostId);
        if (!currentAccess.hostFullyVisible(hostId)) {
            return List.of();
        }
        List<ProxyRoute> hostRoutes = routes.findByHostId(hostId);
        return certificates.findByHostId(hostId).stream()
                .sorted(java.util.Comparator.comparing(ProxyCertificate::getName))
                .map(c -> toView(c, hostRoutes)).toList();
    }

    @Auditable(action = "PROXY_CERT_UPLOAD", targetType = "PROXY_CERT", targetId = "#result.id()",
            details = "{'name': #result.name(), 'domains': #result.domains()}")
    public ProxyCertificateView upload(UUID hostId, ProxyCertificateRequest request) {
        access.requireAdminOperate(hostId);
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > 100 || hasControl(name)) {
            throw new ProxyException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "The name must be 1-100 characters");
        }
        CertificateMaterial material = CertificateParser.parse(request.certPem(), request.keyPem(), clock.instant());
        return sync.exclusive(hostId, () -> tx.execute(status -> {
            if (certificates.existsByHostIdAndName(hostId, name)) {
                throw new ProxyException(HttpStatus.CONFLICT, "CERT_NAME_TAKEN", "A certificate with this name exists");
            }
            ProxyCertificate saved = certificates.save(ProxyCertificate.create(hostId, name, material.certPem(),
                    encrypt(material.keyPem()), material.domains(), material.notAfter()));
            return toView(saved, List.of());
        }));
    }

    /** Replaces chain and key; the new domains must still cover every route using the certificate. Then the proxy is synced. */
    @Auditable(action = "PROXY_CERT_RENEW", targetType = "PROXY_CERT", targetId = "#certificateId",
            details = "{'name': #result.name(), 'domains': #result.domains()}")
    public ProxyCertificateView renew(UUID hostId, UUID certificateId, ProxyCertificateRenewRequest request) {
        access.requireAdminOperate(hostId);
        CertificateMaterial material = CertificateParser.parse(request.certPem(), request.keyPem(), clock.instant());
        return sync.exclusive(hostId, () -> inTransactionThenRepair(hostId, () -> {
            ProxyCertificate cert = find(hostId, certificateId);
            List<ProxyRoute> users = usedBy(certificateId, routes.findByHostId(hostId));
            for (ProxyRoute route : users) {
                for (String name : route.allNames()) {
                    if (!ProxyRouteValidator.covers(material.domains(), name)) {
                        throw new ProxyException(HttpStatus.CONFLICT, "CERT_DOES_NOT_COVER_HOST",
                                "The new certificate does not cover " + name + " of a route using it");
                    }
                }
            }
            cert.replace(material.certPem(), encrypt(material.keyPem()), material.domains(), material.notAfter());
            certificates.saveAndFlush(cert);
            if (instances.findByHostId(hostId).isPresent()) {
                sync.sync(hostId);
            }
            return toView(cert, users);
        }));
    }

    @Auditable(action = "PROXY_CERT_DELETE", targetType = "PROXY_CERT", targetId = "#certificateId")
    public void delete(UUID hostId, UUID certificateId) {
        access.requireAdminOperate(hostId);
        sync.exclusive(hostId, () -> inTransactionThenRepair(hostId, () -> {
            ProxyCertificate cert = find(hostId, certificateId);
            List<ProxyRoute> users = usedBy(certificateId, routes.findByHostId(hostId));
            if (!users.isEmpty()) {
                throw new ProxyException(HttpStatus.CONFLICT, "CERTIFICATE_IN_USE",
                        "The certificate is used by routes", users.stream().map(ProxyRoute::getHostname).toList());
            }
            certificates.delete(cert);
            certificates.flush();
            // the private key must leave the proxy container as well; a failure rolls the row deletion back
            sync.purgeCertificateFiles(hostId, List.of(certificateId));
            return null;
        }));
    }

    /**
     * Runs {@code action} in a transaction; the caller holds the host's proxy lock, which therefore outlives the
     * commit. If it fails, the database change is rolled back, but the proxy container may already have been written
     * to, so the container is synced again from the committed state (best effort, logged, never masking the original
     * error).
     */
    private <T> T inTransactionThenRepair(UUID hostId, java.util.function.Supplier<T> action) {
        try {
            return tx.execute(status -> action.get());
        } catch (RuntimeException e) {
            if (e instanceof ProxyException pe && pe.status().is4xxClientError()) {
                throw e; // refused before anything was written
            }
            try {
                if (instances.findByHostId(hostId).isPresent()) {
                    sync.sync(hostId);
                }
            } catch (RuntimeException repair) {
                log.warn("Could not restore the proxy files of host {} after a failed certificate change: {}", hostId,
                        repair.getClass().getSimpleName());
            }
            throw e;
        }
    }

    private ProxyCertificate find(UUID hostId, UUID certificateId) {
        return certificates.findById(certificateId).filter(c -> c.getHostId().equals(hostId))
                .orElseThrow(() -> new ProxyException(HttpStatus.NOT_FOUND, "CERT_NOT_FOUND", "Certificate not found"));
    }

    private byte[] encrypt(String keyPem) {
        try {
            return cipher.encrypt(keyPem);
        } catch (EncryptionKeyMissingException e) {
            throw new ProxyException(HttpStatus.CONFLICT, "ENCRYPTION_KEY_MISSING",
                    "No encryption key is configured, certificate keys cannot be stored");
        }
    }

    private static List<ProxyRoute> usedBy(UUID certificateId, List<ProxyRoute> hostRoutes) {
        return hostRoutes.stream().filter(r -> certificateId.equals(r.getCertificateId())).toList();
    }

    private static ProxyCertificateView toView(ProxyCertificate c, List<ProxyRoute> hostRoutes) {
        List<String> used = usedBy(c.getId(), hostRoutes).stream().map(ProxyRoute::getHostname).sorted().toList();
        return new ProxyCertificateView(c.getId(), c.getName(), c.getDomains(), c.getNotAfter(), used);
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }
}
