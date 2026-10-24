package dev.bethge.shipir.registry;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Feeds stored registry credentials to compose runs; a missing encryption key degrades to "no logins". */
@Component
class RegistryAuthSourceAdapter implements RegistryAuthSource {

    private static final Logger log = LoggerFactory.getLogger(RegistryAuthSourceAdapter.class);

    private final RegistryCredentialService service;

    RegistryAuthSourceAdapter(RegistryCredentialService service) {
        this.service = service;
    }

    @Override
    public List<Auth> auths() {
        try {
            return service.decryptedCredentials().stream()
                    .map(a -> new Auth(a.registryHost(), a.username(), a.secret()))
                    .toList();
        } catch (RegistryException e) {
            log.warn("Registry credentials exist but cannot be used for compose runs: {}", e.code());
            return List.of();
        }
    }
}
