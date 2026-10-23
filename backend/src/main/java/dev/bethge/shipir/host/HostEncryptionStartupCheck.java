package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.SecretCipher;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** Fails startup when TCP hosts exist but their TLS material cannot be decrypted. */
@Component
class HostEncryptionStartupCheck implements SmartInitializingSingleton {

    private final DockerHostRepository hosts;
    private final SecretCipher cipher;

    HostEncryptionStartupCheck(DockerHostRepository hosts, SecretCipher cipher) {
        this.hosts = hosts;
        this.cipher = cipher;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!cipher.isConfigured() && hosts.existsByConnectionType(ConnectionType.TCP)) {
            throw new IllegalStateException("TCP Docker hosts are stored but shipir.encryption-key "
                    + "(SHIPIR_ENCRYPTION_KEY) is not set; set the key they were encrypted with "
                    + "(generate one with: openssl rand -base64 32)");
        }
    }
}
