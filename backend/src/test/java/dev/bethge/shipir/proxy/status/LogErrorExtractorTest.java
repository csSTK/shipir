package dev.bethge.shipir.proxy.status;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogErrorExtractorTest {

    @Test
    void picksTheLatestErrorLineThatMentionsTheDomain() {
        List<String> lines = List.of(
                "2030-01-01T00:00:00Z ERR Unable to obtain ACME certificate for domains error=\"first failure\" domain=shop.example.com",
                "2030-01-01T00:00:01Z INF Configuration loaded",
                "2030-01-01T00:00:02Z ERR Unable to obtain ACME certificate for domains error=\"rate limited\" domain=shop.example.com");

        assertThat(LogErrorExtractor.lastError(lines, "shop.example.com")).hasValueSatisfying(m ->
                assertThat(m).contains("rate limited").doesNotContain("first failure"));
    }

    @Test
    void readsCaddyJsonErrorLines() {
        List<String> lines = List.of(
                "{\"level\":\"error\",\"logger\":\"tls.obtain\",\"msg\":\"could not get certificate\",\"identifier\":\"shop.example.com\",\"error\":\"dial tcp: lookup failed\"}");

        assertThat(LogErrorExtractor.lastError(lines, "shop.example.com")).hasValueSatisfying(m ->
                assertThat(m).contains("lookup failed"));
    }

    @Test
    void ignoresInfoLinesAndOtherDomains() {
        List<String> lines = List.of(
                "2030-01-01T00:00:00Z INF Certificate obtained for shop.example.com",
                "2030-01-01T00:00:01Z ERR Unable to obtain ACME certificate domain=other.example.com");

        assertThat(LogErrorExtractor.lastError(lines, "shop.example.com")).isEmpty();
    }

    @Test
    void doesNotMatchADomainThatOnlyEndsWithTheSameText() {
        List<String> lines = List.of("2030-01-01T00:00:00Z ERR failed for evilshop.example.com");

        assertThat(LogErrorExtractor.lastError(lines, "shop.example.com")).isEmpty();
    }

    @Test
    void hostnamesOfOtherRoutesInTheSameLineAreRedacted() {
        List<String> lines = List.of(
                "2030-01-01T00:00:00Z ERR Unable to obtain ACME certificate domains=[shop.example.com] error=\"no cert for shop.example.com from secret-admin.corp.example.net 10.1.2.3 via acme-v02.api.letsencrypt.org\"");

        String message = LogErrorExtractor.lastError(lines, "shop.example.com").orElseThrow();

        assertThat(message).contains("shop.example.com").doesNotContain("secret-admin").doesNotContain("10.1.2.3")
                .contains("letsencrypt.org");
    }

    @Test
    void messageIsCappedAndFreeOfControlCharactersAndLongTokens() {
        String token = "A".repeat(80);
        String line = "ERR shop.example.com \u001b[31mred\u001b[0m\ttab " + token + " " + "x".repeat(1000);

        String message = LogErrorExtractor.lastError(List.of(line), "shop.example.com").orElseThrow();

        assertThat(message.length()).isLessThanOrEqualTo(LogErrorExtractor.MAX_MESSAGE);
        assertThat(message).doesNotContain(token).doesNotContain("\u001b").doesNotContain("\t").doesNotContain("\n");
    }

    @Test
    void emptyInputGivesNothing() {
        assertThat(LogErrorExtractor.lastError(List.of(), "shop.example.com")).isEmpty();
    }

    private static String message(String line) {
        return LogErrorExtractor.lastError(List.of(line), "shop.example.com").orElseThrow();
    }

    @Test
    void onlyTheErrorAndMessageFieldsAreUsedOtherFieldsAreDropped() {
        String m = message("time=2030 level=error msg=\"Unable to obtain ACME certificate\" routerName=tenant-b-router rule=\"Host(`shop.example.com`)\" "
                + "providerName=acme container=tenant-b-db error=\"rate limited\"");

        assertThat(m).isEqualTo("Unable to obtain ACME certificate: rate limited");
    }

    @Test
    void consoleFormatKeepsOnlyTheTextBeforeTheFirstField() {
        String m = message("2030-01-01T00:00:00Z ERR Unable to obtain ACME certificate for domains routerName=tenant-b-router@docker domain=shop.example.com");

        assertThat(m).isEqualTo("Unable to obtain ACME certificate for domains");
    }

    @Test
    void ipv6AddressesAreRedacted() {
        String m = message("level=error error=\"shop.example.com dial tcp [2001:db8::1]:443 and fe80::1 and ::1 refused\"");

        assertThat(m).doesNotContain("2001").doesNotContain("db8").doesNotContain("fe80").doesNotContain("::1");
    }

    @Test
    void emailAddressesAreRedacted() {
        String m = message("level=error error=\"shop.example.com account ops@tenant-b.example.net rejected\"");

        assertThat(m).doesNotContain("ops").doesNotContain("tenant-b").doesNotContain("@");
    }

    @Test
    void providerSuffixedNamesAreRedacted() {
        String m = message("level=error error=\"shop.example.com router tenant-b-web@docker service api@file and noop@internal failed\"");

        assertThat(m).doesNotContain("tenant-b").doesNotContain("@docker").doesNotContain("api").doesNotContain("@file")
                .doesNotContain("noop").doesNotContain("@internal");
    }

    @Test
    void urlsWithPathsAndQueriesAreRedactedAsAWhole() {
        String m = message("level=error error=\"shop.example.com fetch https://tenant-b.example.net/.well-known/acme-challenge/tok?x=secret failed, also /var/lib/x/y\"");

        assertThat(m).doesNotContain("tenant-b").doesNotContain("acme-challenge").doesNotContain("secret")
                .doesNotContain("/var").doesNotContain("?").doesNotContain("://");
    }

    @Test
    void aJsonLineWithoutMessageFieldsGivesAGenericText() {
        String m = message("{\"level\":\"error\",\"identifier\":\"shop.example.com\",\"other\":\"tenant-b-db\"}");

        assertThat(m).doesNotContain("tenant-b").doesNotContain("{");
    }
}
