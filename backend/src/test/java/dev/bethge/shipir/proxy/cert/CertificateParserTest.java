package dev.bethge.shipir.proxy.cert;

import dev.bethge.shipir.proxy.ProxyException;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validation of uploaded certificates with real generated certificates and keys. */
class CertificateParserTest {

    private static final TestCerts.Pair RSA = TestCerts.valid("RSA", "example.com", "dns:Example.com,dns:www.example.com,dns:*.apps.example.com,ip:10.0.0.1");
    private static final TestCerts.Pair EC = TestCerts.valid("EC", "ec.example.com", "dns:ec.example.com");
    private static final TestCerts.Pair ED = TestCerts.valid("Ed25519", "ed.example.com", "dns:ed.example.com");
    private static final TestCerts.Pair CN_ONLY = TestCerts.valid("RSA", "Only-Cn.Example.com", null);

    private static CertificateMaterial parse(String cert, String key) {
        return CertificateParser.parse(cert, key, Instant.now());
    }

    private static void assertRejected(String cert, String key, String code) {
        assertThatThrownBy(() -> parse(cert, key))
                .isInstanceOfSatisfying(ProxyException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).doesNotContain("BEGIN");
                    String[] lines = key.strip().split("\n");
                    if (lines.length > 2) {
                        assertThat(e.getMessage()).doesNotContain(lines[1]);
                    }
                });
    }

    @Test
    void acceptsAnRsaChainAndReadsSansLowercasedWithoutIpAddresses() {
        CertificateMaterial m = parse(RSA.certPem(), RSA.keyPem());

        assertThat(m.domains()).containsExactly("example.com", "www.example.com", "*.apps.example.com");
        assertThat(m.notAfter()).isAfter(Instant.now().plusSeconds(20L * 86400)).isBefore(Instant.now().plusSeconds(31L * 86400));
        assertThat(m.certPem()).startsWith("-----BEGIN CERTIFICATE-----\n").endsWith("-----END CERTIFICATE-----\n");
        assertThat(m.keyPem()).isEqualTo(RSA.keyPem());
    }

    @Test
    void acceptsEcAndEd25519() {
        assertThat(parse(EC.certPem(), EC.keyPem()).domains()).containsExactly("ec.example.com");
        assertThat(parse(ED.certPem(), ED.keyPem()).domains()).containsExactly("ed.example.com");
    }

    @Test
    void fallsBackToTheCommonNameWithoutSans() {
        assertThat(parse(CN_ONLY.certPem(), CN_ONLY.keyPem()).domains()).containsExactly("only-cn.example.com");
    }

    @Test
    void acceptsAChainWithSeveralCertificatesLeafFirstAndNormalisesIt() {
        String chain = "  \r\n" + RSA.certPem().replace("\n", "\r\n") + "\n\n" + EC.certPem();

        CertificateMaterial m = parse(chain, RSA.keyPem());

        assertThat(m.domains()).contains("example.com");
        assertThat(m.certPem().split("-----BEGIN CERTIFICATE-----", -1)).hasSize(3);
        assertThat(m.certPem()).doesNotContain("\r");
    }

    @Test
    void acceptsPkcs1RsaAndSec1EcKeys() throws Exception {
        byte[] rsaPkcs1 = PrivateKeyInfo.getInstance(Base64.getMimeDecoder().decode(body(RSA.keyPem()))).parsePrivateKey()
                .toASN1Primitive().getEncoded();
        assertThat(parse(RSA.certPem(), TestCerts.pem("RSA PRIVATE KEY", rsaPkcs1)).domains()).contains("example.com");

        PrivateKeyInfo ecInfo = PrivateKeyInfo.getInstance(Base64.getMimeDecoder().decode(body(EC.keyPem())));
        ECPrivateKey inner = ECPrivateKey.getInstance(ecInfo.parsePrivateKey());
        ECPrivateKey sec1 = new ECPrivateKey(256, inner.getKey(), ecInfo.getPrivateKeyAlgorithm().getParameters());
        assertThat(parse(EC.certPem(), TestCerts.pem("EC PRIVATE KEY", sec1.toASN1Primitive().getEncoded())).domains())
                .containsExactly("ec.example.com");
    }

    @Test
    void rejectsAKeyThatDoesNotBelongToTheCertificate() {
        assertRejected(RSA.certPem(), CN_ONLY.keyPem(), "KEY_MISMATCH");
        assertRejected(RSA.certPem(), EC.keyPem(), "KEY_MISMATCH");
        assertRejected(EC.certPem(), ED.keyPem(), "KEY_MISMATCH");
    }

    @Test
    void rejectsRsaKeysBelow2048Bits() {
        TestCerts.Pair weak = TestCerts.rsa(1024, "weak.example.com", "dns:weak.example.com");

        assertRejected(weak.certPem(), weak.keyPem(), "INVALID_KEY");
    }

    @Test
    void storesTheKeyReEmittedFromTheParsedDerNotTheSuppliedText() {
        String noisy = "\n  " + RSA.keyPem().replace("\n", "\r\n") + "\n\n";

        assertThat(parse(RSA.certPem(), noisy).keyPem()).isEqualTo(RSA.keyPem());
    }

    @Test
    void rejectsAnExpiredAndANotYetValidCertificate() {
        TestCerts.Pair expired = TestCerts.expired("old.example.com", "dns:old.example.com");
        TestCerts.Pair future = TestCerts.notYetValid("new.example.com", "dns:new.example.com");

        assertRejected(expired.certPem(), expired.keyPem(), "CERTIFICATE_EXPIRED");
        assertRejected(future.certPem(), future.keyPem(), "CERTIFICATE_NOT_YET_VALID");
    }

    @Test
    void rejectsGarbageAsCertificateAndAsKey() {
        assertRejected("not a pem at all", RSA.keyPem(), "INVALID_CERTIFICATE");
        assertRejected("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n", RSA.keyPem(), "INVALID_CERTIFICATE");
        assertRejected("-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n", RSA.keyPem(), "INVALID_CERTIFICATE");
        assertRejected(RSA.certPem() + "trailing junk", RSA.keyPem(), "INVALID_CERTIFICATE");
        assertRejected("junk\n" + RSA.certPem(), RSA.keyPem(), "INVALID_CERTIFICATE");
        assertRejected(RSA.certPem(), "not a key", "INVALID_KEY");
        assertRejected(RSA.certPem(), "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n", "INVALID_KEY");
        assertRejected(RSA.certPem(), RSA.keyPem() + RSA.keyPem(), "INVALID_KEY");
        assertRejected(RSA.certPem(), RSA.certPem(), "INVALID_KEY");
    }

    @Test
    void rejectsAPrivateKeyPastedIntoTheCertificateField() {
        assertRejected(RSA.keyPem(), RSA.keyPem(), "INVALID_CERTIFICATE");
        assertRejected(RSA.certPem() + RSA.keyPem(), RSA.keyPem(), "INVALID_CERTIFICATE");
    }

    @Test
    void rejectsEncryptedKeys() {
        String pkcs8Encrypted = TestCerts.pem("ENCRYPTED PRIVATE KEY", new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        String legacy = "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,0123456789ABCDEF0123456789ABCDEF\n\nAAAA\n-----END RSA PRIVATE KEY-----\n";

        assertRejected(RSA.certPem(), pkcs8Encrypted, "KEY_ENCRYPTED");
        assertRejected(RSA.certPem(), legacy, "KEY_ENCRYPTED");
    }

    @Test
    void rejectsOversizedInput() {
        String bigCert = RSA.certPem() + " ".repeat(CertificateParser.MAX_CERT_BYTES);
        String bigKey = RSA.keyPem() + " ".repeat(CertificateParser.MAX_KEY_BYTES);

        assertRejected(bigCert, RSA.keyPem(), "CERT_TOO_LARGE");
        assertRejected(RSA.certPem(), bigKey, "CERT_TOO_LARGE");
    }

    @Test
    void rejectsMoreThanTenCertificatesInTheChain() {
        String chain = RSA.certPem().repeat(CertificateParser.MAX_CHAIN + 1);

        assertRejected(chain, RSA.keyPem(), "INVALID_CERTIFICATE");
    }

    @Test
    void rejectsBlankInput() {
        assertRejected("", RSA.keyPem(), "INVALID_CERTIFICATE");
        assertThatThrownBy(() -> parse(RSA.certPem(), " ")).isInstanceOf(ProxyException.class);
    }

    private static String body(String pem) {
        return pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    }
}
