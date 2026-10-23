package dev.bethge.shipir.docker.tls;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.tls.TestPki.Issued;
import dev.bethge.shipir.docker.tls.TestPki.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemSslConfigTest {

    private static final Issued CA = TestPki.ca("test-ca", KeyType.EC);

    private static TlsMaterial material(Issued client, String keyPem) {
        return new TlsMaterial(CA.certPem(), client.certPem(), keyPem);
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void acceptsPkcs8Key(KeyType type) throws Exception {
        Issued client = TestPki.client(CA, "client", type);

        assertThat(new PemSslConfig(material(client, client.keyPkcs8Pem())).getSSLContext()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void acceptsTraditionalKey(KeyType type) throws Exception {
        // PKCS#1 for RSA, SEC1 for EC
        Issued client = TestPki.client(CA, "client", type);

        assertThat(new PemSslConfig(material(client, client.keyTraditionalPem())).getSSLContext()).isNotNull();
    }

    @Test
    void acceptsCertificateChainAndSurroundingText() {
        Issued client = TestPki.client(CA, "client", KeyType.RSA);
        String noisy = "# comment\n" + client.certPem() + "\n" + CA.certPem() + "\n";

        assertThatCode(() -> TlsMaterialValidator.validate(
                new TlsMaterial(CA.certPem(), noisy, client.keyPkcs8Pem()))).doesNotThrowAnyException();
    }

    @Test
    void validatorAcceptsGoodMaterial() {
        Issued client = TestPki.client(CA, "client", KeyType.EC);

        assertThatCode(() -> TlsMaterialValidator.validate(material(client, client.keyPkcs8Pem())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidMaterialWithoutLeakingPem() {
        Issued client = TestPki.client(CA, "client", KeyType.RSA);
        String garbageBody = "-----BEGIN PRIVATE KEY-----\nTm90IGEga2V5IGF0IGFsbA==\n-----END PRIVATE KEY-----\n";
        String encrypted = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIIBHDBXBgkqhkiG9w0BBQ0wSjApBgkqhkiG9w0BBQwwHAQIabcdefgh\n-----END ENCRYPTED PRIVATE KEY-----\n";
        String truncated = client.keyPkcs8Pem().substring(0, 80);
        List<TlsMaterial> bad = List.of(
                material(client, null),
                material(client, ""),
                material(client, "   "),
                material(client, "not a pem at all"),
                material(client, garbageBody),
                material(client, encrypted),
                material(client, truncated),
                // certificate in the key slot
                material(client, client.certPem()),
                // key in the certificate slot
                new TlsMaterial(CA.certPem(), client.keyPkcs8Pem(), client.keyPkcs8Pem()),
                new TlsMaterial(CA.certPem(), null, client.keyPkcs8Pem()),
                new TlsMaterial(CA.certPem(), "garbage", client.keyPkcs8Pem()),
                new TlsMaterial(null, client.certPem(), client.keyPkcs8Pem()),
                new TlsMaterial("garbage", client.certPem(), client.keyPkcs8Pem()),
                // a key where the CA should be
                new TlsMaterial(client.keyPkcs8Pem(), client.certPem(), client.keyPkcs8Pem()));

        for (TlsMaterial m : bad) {
            for (Function<TlsMaterial, Object> action : List.<Function<TlsMaterial, Object>>of(
                    x -> { TlsMaterialValidator.validate(x); return null; }, PemSslConfig::new)) {
                assertThatThrownBy(() -> action.apply(m))
                        .isInstanceOfSatisfying(DockerConnectionException.class,
                                e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.INVALID_TLS_MATERIAL))
                        .satisfies(e -> {
                            String text = e.getMessage() + " " + e.getCause();
                            assertThat(text).doesNotContain("BEGIN").doesNotContain("PRIVATE KEY")
                                    .doesNotContain("Tm90IGEga2V5");
                            assertThat(client.keyPkcs8Pem().lines().skip(1).findFirst().orElseThrow())
                                    .satisfies(line -> assertThat(text).doesNotContain(line));
                        });
            }
        }
    }
}
