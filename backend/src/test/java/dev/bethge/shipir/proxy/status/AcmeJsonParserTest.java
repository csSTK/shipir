package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.proxy.cert.TestCerts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcmeJsonParserTest {

    static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** One entry the way Traefik writes it: certificate and key are base64 of PEM. */
    static String entry(String main, List<String> sans, TestCerts.Pair pair) {
        String sanJson = sans.isEmpty() ? "" : ",\"sans\":[" + String.join(",", sans.stream().map(s -> "\"" + s + "\"").toList()) + "]";
        return "{\"domain\":{\"main\":\"" + main + "\"" + sanJson + "},\"certificate\":\"" + b64(pair.certPem())
                + "\",\"key\":\"" + b64(pair.keyPem()) + "\",\"Store\":\"default\"}";
    }

    static byte[] file(String resolver, String... entries) {
        return ("{\"" + resolver + "\":{\"Account\":{\"Email\":\"ops@example.com\",\"PrivateKey\":\"ACCOUNTKEYMATERIAL1234567890\","
                + "\"KeyType\":\"4096\"},\"Certificates\":[" + String.join(",", entries) + "]}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsDomainsAndNotAfterOfEveryCertificate() {
        TestCerts.Pair shop = TestCerts.valid("RSA", "shop.example.com", "dns:shop.example.com");
        TestCerts.Pair wild = TestCerts.valid("RSA", "example.org", "dns:example.org,dns:*.example.org");

        List<IssuedCert> certs = AcmeJsonParser.parse(file("letsencrypt-http",
                entry("shop.example.com", List.of(), shop), entry("example.org", List.of("*.example.org"), wild)));

        assertThat(certs).hasSize(2);
        assertThat(certs.get(0).domains()).containsExactly("shop.example.com");
        assertThat(certs.get(1).domains()).containsExactly("example.org", "*.example.org");
        assertThat(certs.get(0).notAfter()).isAfter(Instant.now().plusSeconds(20L * 86400)).isBefore(Instant.now().plusSeconds(31L * 86400));
    }

    @Test
    void readsExpiredCertificatesToo() {
        TestCerts.Pair old = TestCerts.expired("old.example.com", "dns:old.example.com");

        List<IssuedCert> certs = AcmeJsonParser.parse(file("letsencrypt-http", entry("old.example.com", List.of(), old)));

        assertThat(certs).singleElement().satisfies(c -> assertThat(c.notAfter()).isBefore(Instant.now()));
    }

    @Test
    void mergesAllResolversOfTheFile() {
        TestCerts.Pair a = TestCerts.valid("RSA", "a.example.com", "dns:a.example.com");
        TestCerts.Pair b = TestCerts.valid("RSA", "b.example.com", "dns:b.example.com");
        String two = "{\"r1\":{\"Certificates\":[" + entry("a.example.com", List.of(), a) + "]},"
                + "\"r2\":{\"Certificates\":[" + entry("b.example.com", List.of(), b) + "]}}";

        assertThat(AcmeJsonParser.parse(two.getBytes(StandardCharsets.UTF_8))).hasSize(2);
    }

    @Test
    void emptyStoreAndNullCertificatesGiveNoEntries() {
        assertThat(AcmeJsonParser.parse("{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(AcmeJsonParser.parse("{\"r\":{\"Account\":{},\"Certificates\":null}}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(AcmeJsonParser.parse(new byte[0])).isEmpty();
    }

    @Test
    void skipsAnEntryWithAnUnreadableCertificateButKeepsTheOthers() {
        TestCerts.Pair ok = TestCerts.valid("RSA", "ok.example.com", "dns:ok.example.com");
        String bad = "{\"domain\":{\"main\":\"bad.example.com\"},\"certificate\":\"" + b64("not a certificate") + "\",\"key\":\"x\"}";

        List<IssuedCert> certs = AcmeJsonParser.parse(file("r", bad, entry("ok.example.com", List.of(), ok)));

        assertThat(certs).singleElement().satisfies(c -> assertThat(c.domains()).containsExactly("ok.example.com"));
    }

    @Test
    void rejectsAFileOverTheSizeCap() {
        byte[] big = new byte[AcmeJsonParser.MAX_BYTES + 1];

        assertThatThrownBy(() -> AcmeJsonParser.parse(big)).isInstanceOf(StatusParseException.class);
    }

    @Test
    void rejectsMoreEntriesThanTheCap() {
        String one = "{\"domain\":{\"main\":\"a.example.com\"},\"certificate\":\"AAAA\",\"key\":\"k\"}";
        String many = String.join(",", java.util.Collections.nCopies(AcmeJsonParser.MAX_ENTRIES + 1, one));

        assertThatThrownBy(() -> AcmeJsonParser.parse(file("r", many))).isInstanceOf(StatusParseException.class);
    }

    @Test
    void malformedJsonFailsWithFixedTextThatNeverContainsTheDocument() {
        TestCerts.Pair p = TestCerts.valid("RSA", "k.example.com", "dns:k.example.com");
        String keyB64 = b64(p.keyPem());
        String broken = "{\"r\":{\"Certificates\":[{\"key\":\"" + keyB64 + "\",\"domain\":{\"main\":" + "<<<broken";

        assertThatThrownBy(() -> AcmeJsonParser.parse(broken.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(StatusParseException.class)
                .hasMessageNotContaining(keyB64.substring(0, 40))
                .hasMessageNotContaining("k.example.com")
                .hasNoCause();
    }

    @Test
    void privateKeyMaterialNeverAppearsInTheParsedResult() {
        TestCerts.Pair p = TestCerts.valid("RSA", "secret.example.com", "dns:secret.example.com");
        String keyBody = p.keyPem().replaceAll("-----[A-Z ]+-----|\\s", "");

        List<IssuedCert> certs = AcmeJsonParser.parse(file("r", entry("secret.example.com", List.of(), p)));

        String everything = certs + " " + certs.get(0).domains() + " " + certs.get(0).notAfter();
        assertThat(everything).doesNotContain(keyBody.substring(0, 40)).doesNotContain("PRIVATE KEY")
                .doesNotContain("ACCOUNTKEYMATERIAL").doesNotContain(b64(p.keyPem()).substring(0, 40));
        // the type cannot even carry a key: only names and an end date
        assertThat(IssuedCert.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly("domains", "notAfter");
    }

    @Test
    void nonObjectElementsInCertificatesAreSkippedAndLaterEntriesStillCount() {
        TestCerts.Pair ok = TestCerts.valid("RSA", "later.example.com", "dns:later.example.com");
        String doc = "{\"r\":{\"Certificates\":[1,\"x\",[1,[2]],null," + entry("later.example.com", List.of(), ok) + "]}}";

        assertThat(AcmeJsonParser.parse(doc.getBytes(StandardCharsets.UTF_8)))
                .singleElement().satisfies(c -> assertThat(c.domains()).containsExactly("later.example.com"));
    }

    @Test
    void nonStringElementsInSansAreSkippedAndTheRestOfTheDomainStillCounts() {
        TestCerts.Pair p = TestCerts.valid("RSA", "a.example.com", "dns:a.example.com");
        String e = "{\"domain\":{\"main\":\"a.example.com\",\"sans\":[1,{\"x\":[1]},[\"y\"],\"b.example.com\"]},\"certificate\":\""
                + b64(p.certPem()) + "\",\"key\":\"k\"}";
        TestCerts.Pair q = TestCerts.valid("RSA", "c.example.com", "dns:c.example.com");

        List<IssuedCert> certs = AcmeJsonParser.parse(file("r", e, entry("c.example.com", List.of(), q)));

        assertThat(certs).hasSize(2);
        assertThat(certs.get(0).domains()).containsExactly("a.example.com", "b.example.com");
    }
}
