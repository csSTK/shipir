package dev.bethge.shipir.host;

import dev.bethge.shipir.common.security.EncryptionProperties;
import dev.bethge.shipir.common.security.SecretCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HostEncryptionStartupCheckTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final DockerHostRepository hosts = mock(DockerHostRepository.class);

    @Test
    void failsStartupWhenTcpHostsExistAndNoKeyIsConfigured() {
        when(hosts.existsByConnectionType(ConnectionType.TCP)).thenReturn(true);
        var check = new HostEncryptionStartupCheck(hosts, new SecretCipher(new EncryptionProperties(null)));

        assertThatThrownBy(check::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPIR_ENCRYPTION_KEY");
    }

    @Test
    void startsWhenNoTcpHostsExistWithoutKey() {
        when(hosts.existsByConnectionType(ConnectionType.TCP)).thenReturn(false);
        var check = new HostEncryptionStartupCheck(hosts, new SecretCipher(new EncryptionProperties(null)));

        assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void startsWhenTcpHostsExistAndKeyIsConfigured() {
        when(hosts.existsByConnectionType(ConnectionType.TCP)).thenReturn(true);
        var check = new HostEncryptionStartupCheck(hosts, new SecretCipher(new EncryptionProperties(KEY)));

        assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}
