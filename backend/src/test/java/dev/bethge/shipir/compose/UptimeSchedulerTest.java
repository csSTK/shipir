package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import dev.bethge.shipir.common.net.EgressHttpClient;
import dev.bethge.shipir.common.net.EgressPolicy;
import dev.bethge.shipir.common.net.EgressProperties;
import dev.bethge.shipir.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scheduler tick against a stub server: due selection, result recording, flapping dedupe (DOWN after 2 consecutive
 * failures, UP after one success, notify only on state change), failure isolation and the results purge.
 */
@RecordApplicationEvents
class UptimeSchedulerTest extends DeploymentTestBase {

    @Autowired
    UptimeCheckRepository checks;
    @Autowired
    UptimeResultRepository results;
    @Autowired
    UptimeCheckService service;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-10T03:00:00Z"));
    private UptimeScheduler scheduler;
    private HttpServer stub;
    private volatile int status = 200;
    @Autowired
    ApplicationEvents events;

    private final AtomicInteger hits = new AtomicInteger();
    private String base;
    private UUID deploymentId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notifications");
        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        stub.start();
        base = "http://127.0.0.1:" + stub.getAddress().getPort();
        UptimeProbe probe = new UptimeProbe(new EgressHttpClient(new EgressPolicy(new EgressProperties(true)),
                Duration.ofSeconds(2)));
        scheduler = new UptimeScheduler(checks, service, probe,
                new UptimeProperties(true, Duration.ofSeconds(10), 2, Duration.ofDays(14)), clock);
        Login admin = login("admin");
        String body = mvc.perform(admin.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson("web", YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        deploymentId = UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private UptimeCheck addCheck(String name, String url, boolean enabled) {
        return checks.save(new UptimeCheck(deploymentId, name, url, null, 30, 2, enabled, clock.instant()));
    }

    private void tick() {
        scheduler.tick();
        clock.advance(Duration.ofSeconds(31));
    }

    private int adminNotifications(String type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE u.username = 'admin' AND n.type = ?
                """, Integer.class, type);
    }

    private UptimeCheck reload(UptimeCheck c) {
        return checks.findById(c.getId()).orElseThrow();
    }

    @Test
    void upCheckRecordsAResultAndTurnsUpWithoutNotification() {
        UptimeCheck c = addCheck("home", base + "/", true);
        tick();
        UptimeCheck after = reload(c);
        assertThat(after.getLastStatus()).isEqualTo(UptimeCheck.State.UP);
        assertThat(after.getLastCheckedAt()).isNotNull();
        List<UptimeResult> latest = results.latest(c.getId(), org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(latest).hasSize(1);
        assertThat(latest.getFirst().isOk()).isTrue();
        assertThat(latest.getFirst().getStatusCode()).isEqualTo(200);
        assertThat(adminNotifications("UPTIME_UP") + adminNotifications("UPTIME_DOWN")).isZero();
    }

    @Test
    void singleFailureDoesNotNotifyButTwoConsecutiveDo_OnlyOnce() {
        UptimeCheck c = addCheck("home", base + "/", true);
        tick(); // up
        status = 500;
        tick(); // 1st failure: still UP, no notification
        assertThat(reload(c).getLastStatus()).isEqualTo(UptimeCheck.State.UP);
        assertThat(reload(c).getConsecutiveFailures()).isEqualTo(1);
        assertThat(adminNotifications("UPTIME_DOWN")).isZero();
        tick(); // 2nd: DOWN
        assertThat(reload(c).getLastStatus()).isEqualTo(UptimeCheck.State.DOWN);
        assertThat(adminNotifications("UPTIME_DOWN")).isEqualTo(1);
        tick();
        tick(); // still failing: no further notifications
        assertThat(adminNotifications("UPTIME_DOWN")).isEqualTo(1);
        assertThat(reload(c).getConsecutiveFailures()).isEqualTo(4);
    }

    @Test
    void aSuccessBetweenFailuresResetsTheCounter() {
        UptimeCheck c = addCheck("home", base + "/", true);
        tick();
        status = 500;
        tick();
        status = 200;
        tick();
        status = 500;
        tick();
        assertThat(reload(c).getLastStatus()).isEqualTo(UptimeCheck.State.UP);
        assertThat(adminNotifications("UPTIME_DOWN")).isZero();
    }

    @Test
    void recoveryNotifiesOnceAndNamesTheCheckButNotTheUrl() {
        UptimeCheck c = addCheck("home", base + "/secret-path?token=abc", true);
        status = 500;
        tick();
        tick(); // DOWN from UNKNOWN after 2 failures
        assertThat(adminNotifications("UPTIME_DOWN")).isEqualTo(1);
        status = 200;
        tick();
        tick();
        assertThat(reload(c).getLastStatus()).isEqualTo(UptimeCheck.State.UP);
        assertThat(adminNotifications("UPTIME_UP")).isEqualTo(1);
        List<String> messages = jdbc.queryForList("SELECT message FROM notifications", String.class);
        assertThat(messages).isNotEmpty().allSatisfy(m -> assertThat(m)
                .contains("home").contains("web").doesNotContain("secret-path").doesNotContain("token").doesNotContain("127.0.0.1"));
    }

    @Test
    void notificationsReachOnlyUsersWhoMaySeeTheDeployment() {
        jdbc.update("DELETE FROM permissions WHERE user_id = (SELECT id FROM users WHERE username = 'viewer')");
        addCheck("home", base + "/", true);
        status = 500;
        tick();
        tick();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id
                WHERE u.username = 'viewer' AND n.type = 'UPTIME_DOWN'
                """, Integer.class)).isZero();
        assertThat(adminNotifications("UPTIME_DOWN")).isEqualTo(1);
    }

    @Test
    void notDueAndDisabledChecksAreNotProbed() {
        addCheck("off", base + "/", false);
        tick();
        assertThat(hits).hasValue(0);
        UptimeCheck on = addCheck("on", base + "/", true);
        scheduler.tick();
        assertThat(hits).hasValue(1);
        scheduler.tick(); // same instant: interval (30s) not over
        assertThat(hits).hasValue(1);
        assertThat(reload(on).getLastCheckedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aFailingCheckNeverStopsTheOthers() {
        addCheck("blocked", "http://169.254.169.254/latest", true); // egress denied
        UptimeCheck good = addCheck("good", base + "/", true);
        tick();
        assertThat(reload(good).getLastStatus()).isEqualTo(UptimeCheck.State.UP);
        assertThat(jdbc.queryForObject("SELECT error_code FROM uptime_results WHERE ok = false", String.class))
                .isEqualTo("EGRESS_DENIED");
    }

    @Test
    void egressDeniedIsReportedAsGenericConnectFailureInNotifications() {
        addCheck("blocked", "http://169.254.169.254/latest", true);
        tick();
        tick(); // 2nd failure: DOWN
        assertThat(adminNotifications("UPTIME_DOWN")).isEqualTo(1);
        String message = jdbc.queryForObject("SELECT message FROM notifications WHERE type = 'UPTIME_DOWN' LIMIT 1", String.class);
        assertThat(message).contains("CONNECTION_FAILED").doesNotContain("EGRESS_DENIED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM uptime_results WHERE ok = false LIMIT 1", String.class))
                .isEqualTo("EGRESS_DENIED"); // the stored result keeps the detail for OPERATORs in the UI
    }

    @ParameterizedTest
    @CsvSource({"DNS_FAILED,CONNECTION_FAILED", "CONNECTION_REFUSED,CONNECTION_FAILED", "TLS_ERROR,CONNECTION_FAILED",
            "IO_ERROR,CONNECTION_FAILED", "ERROR,CONNECTION_FAILED", "TIMEOUT,TIMEOUT",
            "UNEXPECTED_STATUS,UNEXPECTED_STATUS"})
    void connectLevelCodesNeverReachNotificationMessageOrWebhookEvent(String detailed, String expectedPublic) {
        UptimeCheck c = addCheck("internal", base + "/", true);
        Instant at = clock.instant();
        UptimeProbe.ProbeResult failure = new UptimeProbe.ProbeResult(false, null, null, detailed);
        service.record(c.getId(), failure, at);
        service.record(c.getId(), failure, at.plusSeconds(31)); // 2nd failure: DOWN
        String message = jdbc.queryForObject("SELECT message FROM notifications WHERE type = 'UPTIME_DOWN' LIMIT 1", String.class);
        assertThat(message).contains(expectedPublic);
        List<String> published = events.stream(UptimeStateChangedEvent.class).map(UptimeStateChangedEvent::errorCode).toList();
        assertThat(published).containsExactly(expectedPublic); // webhook payload errorCode is built from this event
        if (!detailed.equals(expectedPublic)) {
            assertThat(message).doesNotContain(detailed);
        }
        assertThat(jdbc.queryForObject("SELECT error_code FROM uptime_results WHERE ok = false LIMIT 1", String.class))
                .isEqualTo(detailed); // the stored result keeps the detail for OPERATORs in the UI
    }

    @Test
    void resultsOlderThanTheRetentionArePurged() {
        UptimeCheck c = addCheck("home", base + "/", true);
        jdbc.update("INSERT INTO uptime_results (check_id, checked_at, ok, status_code) VALUES (?, ?::timestamptz, true, 200)",
                c.getId(), clock.instant().minus(Duration.ofDays(15)).toString());
        jdbc.update("INSERT INTO uptime_results (check_id, checked_at, ok, status_code) VALUES (?, ?::timestamptz, true, 200)",
                c.getId(), clock.instant().minus(Duration.ofDays(1)).toString());
        scheduler.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uptime_results WHERE checked_at < ?::timestamptz",
                Integer.class, clock.instant().minus(Duration.ofDays(14)).toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM uptime_results", Integer.class)).isEqualTo(2); // 1 old-ish + new
    }
}
