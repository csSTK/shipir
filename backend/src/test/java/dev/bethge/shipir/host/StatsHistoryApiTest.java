package dev.bethge.shipir.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/hosts/{h}/stats/history}: ranges, bounded point counts and the same visibility as live stats. */
class StatsHistoryApiTest extends HostApiTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private UUID host;
    private UUID otherHost;

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    @BeforeEach
    void hosts() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'stats-host', 'LOCAL', id FROM users WHERE username = 'admin'");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "SELECT 'stats-other', 'TCP', 'other.local', 2375, id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'stats-host'", UUID.class);
        otherHost = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'stats-other'", UUID.class);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'web', ?, 'x', id FROM users WHERE username = 'admin'", host);
    }

    /** One sample per minute for the last {@code minutes} minutes (counters grow by one per sample). */
    private void samples(UUID hostId, String container, String project, int minutes) {
        jdbc.update("""
                INSERT INTO stats_samples (host_id, container_id, compose_project, ts, cpu_percent, mem_bytes,
                    net_rx_bytes, net_tx_bytes, blk_read_bytes, blk_write_bytes)
                SELECT ?, ?, ?, now() - make_interval(mins => g), 40, 1000, 1000000 - g, 1000000 - g, 5, 6
                FROM generate_series(0, ?) g
                """, hostId, container, project, minutes - 1);
    }

    private JsonNode history(String user, UUID hostId, String query) throws Exception {
        String body = mvc.perform(get("/api/hosts/{h}/stats/history" + query, hostId)
                        .session(login(user).session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private List<String> containerIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.get("series").forEach(s -> ids.add(s.get("containerId").asText()));
        return ids;
    }

    @ParameterizedTest
    @CsvSource({"1h,60", "6h,360", "24h,1440", "7d,10080", "30d,43200"})
    void everyRangeReturnsAtMostThreeHundredPointsPerContainer(String range, int minutes) throws Exception {
        samples(host, "c-web", "web", minutes + 120);
        JsonNode r = history("admin", host, "?range=" + range);

        assertThat(r.get("range").asText()).isEqualTo(range);
        JsonNode points = r.get("series").get(0).get("points");
        assertThat(points.size()).isBetween(Math.min(minutes, 100), 300);
        assertThat(r.get("bucketSeconds").asInt()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void bucketsAverageCpuAndKeepTheLatestCounters() throws Exception {
        samples(host, "c-web", "web", 24 * 60 + 60);
        JsonNode points = history("admin", host, "?range=24h").get("series").get(0).get("points");

        assertThat(points.size()).isLessThanOrEqualTo(300).isGreaterThan(100);
        long previousRx = -1;
        for (JsonNode p : points) {
            assertThat(p.get("cpuPercent").asDouble()).isEqualTo(40.0);
            assertThat(p.get("memoryBytes").asLong()).isEqualTo(1000);
            assertThat(p.get("blockReadBytes").asLong()).isEqualTo(5);
            assertThat(p.get("networkRxBytes").asLong()).isGreaterThan(previousRx);
            previousRx = p.get("networkRxBytes").asLong();
        }
        // time ascending
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).get("time").asText()).isGreaterThan(points.get(i - 1).get("time").asText());
        }
    }

    @Test
    void shortRangeKeepsTheRecordedResolution() throws Exception {
        samples(host, "c-web", "web", 600);
        JsonNode r = history("admin", host, "?range=1h");
        assertThat(r.get("bucketSeconds").asInt()).isEqualTo(60);
        assertThat(r.get("series").get(0).get("points").size()).isBetween(59, 61);
    }

    @Test
    void samplesOutsideTheRangeAreLeftOut() throws Exception {
        jdbc.update("""
                INSERT INTO stats_samples (host_id, container_id, ts, cpu_percent, mem_bytes, net_rx_bytes,
                    net_tx_bytes, blk_read_bytes, blk_write_bytes)
                VALUES (?, 'old', now() - interval '2 hours', 1, 1, 1, 1, 1, 1)
                """, host);
        assertThat(history("admin", host, "?range=1h").get("series")).isEmpty();
        assertThat(containerIds(history("admin", host, "?range=6h"))).containsExactly("old");
    }

    @Test
    void defaultRangeIsOneHour() throws Exception {
        samples(host, "c-web", "web", 600);
        assertThat(history("admin", host, "").get("range").asText()).isEqualTo("1h");
    }

    @Test
    void containerParameterNarrowsToOneSeries() throws Exception {
        samples(host, "c-web", "web", 30);
        samples(host, "c-other", "other", 30);
        assertThat(containerIds(history("admin", host, ""))).containsExactlyInAnyOrder("c-other", "c-web");
        assertThat(containerIds(history("admin", host, "?container=c-web"))).containsExactly("c-web");
    }

    @Test
    void onlyReturnsSamplesOfTheRequestedHost() throws Exception {
        samples(host, "c-web", "web", 30);
        samples(otherHost, "c-elsewhere", null, 30);
        assertThat(containerIds(history("admin", host, ""))).containsExactly("c-web");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2h", "1H", "all", "-1h", "1h;drop", "9999d"})
    void unknownRangeIs400(String range) throws Exception {
        mvc.perform(get("/api/hosts/{h}/stats/history", host).param("range", range)
                        .session(login("admin").session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "a b", "c'; DROP TABLE stats_samples;--", "a/b", "%2e%2e", "x\ny"})
    void malformedContainerIdIs400(String container) throws Exception {
        mvc.perform(get("/api/hosts/{h}/stats/history", host).param("container", container)
                        .session(login("admin").session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("/api/hosts/{h}/stats/history", UUID.randomUUID()).session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mvc.perform(get("/api/hosts/{h}/stats/history", host)).andExpect(status().isUnauthorized());
    }

    @Test
    void hostWithoutAnyGrantIs404ForViewerAndOperator() throws Exception {
        samples(host, "c-web", "web", 30);
        for (String user : new String[]{"viewer", "operator"}) {
            mvc.perform(get("/api/hosts/{h}/stats/history", host).session(login(user).session()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        }
    }

    @Test
    void grantOnAnotherHostDoesNotRevealThisOne() throws Exception {
        grantHost("viewer", otherHost, "VIEW");
        mvc.perform(get("/api/hosts/{h}/stats/history", host).session(login("viewer").session()))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewerAndOperatorWithAHostGrantSeeEveryContainerOfTheHost() throws Exception {
        samples(host, "c-web", "web", 30);
        samples(host, "c-other", "other", 30);
        samples(host, "c-plain", null, 30);
        grantHost("viewer", host, "VIEW");
        grantHost("operator", host, "OPERATE");
        for (String user : new String[]{"viewer", "operator", "admin"}) {
            assertThat(containerIds(history(user, host, ""))).containsExactlyInAnyOrder("c-other", "c-plain", "c-web");
        }
    }

    @Test
    void deploymentOnlyGrantSeesOnlyTheContainersOfItsDeployment() throws Exception {
        samples(host, "c-web", "web", 30);
        samples(host, "c-other", "other", 30);
        samples(host, "c-plain", null, 30);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) "
                + "SELECT u.id, d.id, 'VIEW' FROM users u, deployments d WHERE u.username = 'viewer' AND d.name = 'web'");

        assertThat(containerIds(history("viewer", host, ""))).containsExactly("c-web");
        assertThat(containerIds(history("viewer", host, "?container=c-web"))).containsExactly("c-web");
    }

    @Test
    void deploymentOnlyGrantGetsAnEmptyAnswerForAnInvisibleContainerNotAnError() throws Exception {
        samples(host, "c-other", "other", 30);
        samples(host, "c-plain", null, 30);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) "
                + "SELECT u.id, d.id, 'VIEW' FROM users u, deployments d WHERE u.username = 'viewer' AND d.name = 'web'");

        // same answer as for a container that never existed: existence is not revealed
        JsonNode hidden = history("viewer", host, "?container=c-other");
        JsonNode missing = history("viewer", host, "?container=never-existed");
        assertThat(hidden.get("series")).isEmpty();
        assertThat(missing.get("series")).isEmpty();
        assertThat(history("viewer", host, "?container=c-plain").get("series")).isEmpty();
    }

    @Test
    void historyOfADeletedContainerStaysVisibleThroughItsDeployment() throws Exception {
        samples(host, "c-gone", "web", 30); // no such container is running any more, the sample carries the project
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) "
                + "SELECT u.id, d.id, 'VIEW' FROM users u, deployments d WHERE u.username = 'viewer' AND d.name = 'web'");
        assertThat(containerIds(history("viewer", host, ""))).containsExactly("c-gone");
    }

    @Test
    void aProjectNamedLikeADeploymentOfAnotherHostIsNotVisibleThroughThatGrant() throws Exception {
        // deployment 'web' lives on `host`, deployment 'api' on otherHost; the viewer holds a grant on each
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'api', ?, 'x', id FROM users WHERE username = 'admin'", otherHost);
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) "
                + "SELECT u.id, d.id, 'VIEW' FROM users u, deployments d "
                + "WHERE u.username = 'viewer' AND d.name IN ('web', 'api')");
        // on otherHost a project that merely carries the name 'web' is not the deployment 'web' of `host`
        samples(otherHost, "c-web-elsewhere", "web", 30);
        samples(otherHost, "c-api", "api", 30);
        samples(host, "c-web", "web", 30);
        samples(host, "c-unmanaged", "unmanaged", 30);

        assertThat(containerIds(history("viewer", otherHost, ""))).containsExactly("c-api");
        assertThat(containerIds(history("viewer", otherHost, "?container=c-web-elsewhere"))).isEmpty();
        assertThat(containerIds(history("viewer", host, ""))).containsExactly("c-web");
    }

    /** {@code count} containers whose newest sample is {@code i} minutes old (c-00 newest). */
    private void manyContainers(UUID hostId, String project, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO stats_samples (host_id, container_id, compose_project, ts, cpu_percent, mem_bytes,
                        net_rx_bytes, net_tx_bytes, blk_read_bytes, blk_write_bytes)
                    SELECT ?, ?, ?, now() - make_interval(mins => ? + g), 1, 1, 1, 1, 1, 1
                    FROM generate_series(0, 4) g
                    """, hostId, project + "-%02d".formatted(i), project, i);
        }
    }

    @Test
    void seriesCountIsCappedMostRecentFirstAndFlaggedAsTruncated() throws Exception {
        manyContainers(host, "m", 60);
        JsonNode r = history("admin", host, "?range=24h");

        assertThat(r.get("truncated").asBoolean()).isTrue();
        assertThat(r.get("seriesTotal").asInt()).isEqualTo(60);
        List<String> ids = containerIds(r);
        assertThat(ids).hasSize(50);
        assertThat(ids.get(0)).isEqualTo("m-00");
        assertThat(ids.get(49)).isEqualTo("m-49");
        assertThat(ids).doesNotContain("m-50", "m-59");
    }

    @Test
    void notTruncatedWhenTheSeriesFitTheCap() throws Exception {
        manyContainers(host, "m", 50);
        JsonNode r = history("admin", host, "?range=24h");
        assertThat(r.get("truncated").asBoolean()).isFalse();
        assertThat(r.get("seriesTotal").asInt()).isEqualTo(50);
        assertThat(containerIds(r)).hasSize(50);
    }

    @Test
    void permissionFilterAppliesBeforeTheSeriesCap() throws Exception {
        manyContainers(host, "other", 60); // newer, invisible to the viewer
        for (int i = 0; i < 3; i++) { // older than every 'other' container, project 'web'
            jdbc.update("""
                    INSERT INTO stats_samples (host_id, container_id, compose_project, ts, cpu_percent, mem_bytes,
                        net_rx_bytes, net_tx_bytes, blk_read_bytes, blk_write_bytes)
                    SELECT ?, ?, 'web', now() - make_interval(mins => 300 + g), 1, 1, 1, 1, 1, 1
                    FROM generate_series(0, 4) g
                    """, host, "web-" + i);
        }
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) "
                + "SELECT u.id, d.id, 'VIEW' FROM users u, deployments d WHERE u.username = 'viewer' AND d.name = 'web'");

        JsonNode viewer = history("viewer", host, "?range=24h");
        assertThat(containerIds(viewer)).containsExactlyInAnyOrder("web-0", "web-1", "web-2");
        assertThat(viewer.get("truncated").asBoolean()).isFalse();
        assertThat(viewer.get("seriesTotal").asInt()).isEqualTo(3);

        JsonNode admin = history("admin", host, "?range=24h");
        assertThat(admin.get("truncated").asBoolean()).isTrue();
        assertThat(admin.get("seriesTotal").asInt()).isEqualTo(63);
    }
}
