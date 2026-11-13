package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.auth.token.TokenCodec;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redeploy webhook: management by OPERATE users, the public {@code POST /api/webhooks/deploy/{token}} trigger. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DeploymentWebhookApiTest extends DeploymentTestBase {

    private static final String TRIGGER = "/api/webhooks/deploy/";

    @MockitoBean
    ComposeOutputStreamService streams;

    @BeforeEach
    void resetWebhooks() {
        when(streams.start(any())).thenReturn((Consumer<String>) new CopyOnWriteArrayList<String>()::add);
        jdbc.update("DELETE FROM api_tokens");
    }

    private String create(Login l, String name) throws Exception {
        String body = mvc.perform(l.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson(name, YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String deployed(Login l) throws Exception {
        String id = create(l, "web");
        mvc.perform(l.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        awaitResults(1);
        commands.clear();
        return id;
    }

    private void awaitResults(int n) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action LIKE '%\\_RESULT'", Integer.class))
                        .isGreaterThanOrEqualTo(n));
    }

    private String enable(Login l, String id) throws Exception {
        String body = mvc.perform(l.write(put(url() + "/" + id + "/webhook")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private MvcResult trigger(String token, String address) throws Exception {
        return mvc.perform(post(TRIGGER + token).with(r -> {
            r.setRemoteAddr(address);
            return r;
        })).andReturn();
    }

    private static String freshAddress() {
        return "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250));
    }

    private List<String> audit() {
        return jdbc.queryForList("SELECT action || ':' || outcome FROM audit_log ORDER BY occurred_at, id", String.class);
    }

    @Test
    void enableShowsTheTokenOnceAndStoresOnlyItsSha256() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");

        assertThat(mvc.perform(op.write(get(url() + "/" + id + "/webhook")).session(op.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andReturn().getResponse().getContentAsString()).doesNotContain("token");

        String body = mvc.perform(op.write(put(url() + "/" + id + "/webhook")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        assertThat(token).matches("shw_[A-Za-z0-9_-]{43}");
        assertThat((String) JsonPath.read(body, "$.path")).isEqualTo(TRIGGER + token);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM deployment_webhooks", String.class))
                .isEqualTo(TokenCodec.sha256Hex(token));
        assertThat(jdbc.queryForList("SELECT row_to_json(w)::text FROM deployment_webhooks w", String.class))
                .noneMatch(row -> row.contains(token));

        String again = mvc.perform(get(url() + "/" + id + "/webhook").session(op.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(again).doesNotContain(token);
        assertThat(audit()).contains("DEPLOYMENT_WEBHOOK_ENABLE:SUCCESS");
        assertThat(jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a", String.class))
                .noneMatch(row -> row.contains(token));
    }

    @Test
    void enablingTwiceIs409() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");
        enable(op, id);

        mvc.perform(op.write(put(url() + "/" + id + "/webhook")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_ALREADY_ENABLED"));
    }

    @Test
    void managementNeedsOperatePermissionOnTheDeployment() throws Exception {
        Login admin = login("admin");
        String d1 = create(admin, "one");
        String d2 = create(admin, "two");
        jdbc.update("DELETE FROM permissions WHERE user_id = (SELECT id FROM users WHERE username = 'operator')");
        grantDeployment("operator", UUID.fromString(d1), "OPERATE");
        Login op = login("operator");

        mvc.perform(op.write(put(url() + "/" + d2 + "/webhook"))).andExpect(status().isNotFound());
        mvc.perform(get(url() + "/" + d2 + "/webhook").session(op.session())).andExpect(status().isNotFound());
        mvc.perform(op.write(put(url() + "/" + d1 + "/webhook"))).andExpect(status().isOk());

        jdbc.update("UPDATE permissions SET level = 'VIEW' WHERE deployment_id = ?", UUID.fromString(d1));
        mvc.perform(op.write(post(url() + "/" + d1 + "/webhook/rotate"))).andExpect(status().isForbidden());
        mvc.perform(op.write(delete(url() + "/" + d1 + "/webhook"))).andExpect(status().isForbidden());

        Login viewer = login("viewer");
        mvc.perform(viewer.write(put(url() + "/" + d1 + "/webhook"))).andExpect(status().isForbidden());
        mvc.perform(get(url() + "/" + d1 + "/webhook").session(viewer.session())).andExpect(status().isForbidden());
    }

    @Test
    void managementRequiresLoginAndCsrf() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");

        mvc.perform(get(url() + "/" + id + "/webhook")).andExpect(status().isUnauthorized());
        mvc.perform(put(url() + "/" + id + "/webhook").session(op.session())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_webhooks", Integer.class)).isZero();
    }

    @Test
    void apiTokenOfAnOperatorMayManageTheWebhookButTheWebhookTokenIsNoBearer() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");
        String body = mvc.perform(op.write(post("/api/account/tokens")).contentType(APPLICATION_JSON)
                .content("{\"name\":\"ci\",\"stepUp\":{\"password\":\"" + PASSWORD + "\"}}")).andReturn().getResponse().getContentAsString();
        String apiToken = JsonPath.read(body, "$.token");

        String webhookBody = mvc.perform(put(url() + "/" + id + "/webhook")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String webhookToken = JsonPath.read(webhookBody, "$.token");

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + webhookToken))
                .andExpect(status().isUnauthorized());
        assertThat(trigger(apiToken, freshAddress()).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void triggerRunsPullUpdateAsSystemWithoutLoginOrCsrfAndAuditsWithoutTheToken() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String token = enable(op, id);

        MvcResult r = trigger(token, freshAddress());

        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.runId")).isNotBlank();
        assertThat(r.getRequest().getSession(false)).isNull();
        awaitResults(2);
        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThat(jdbc.queryForList("SELECT username || ':' || target_type || ':' || target_id || ':' || outcome FROM audit_log "
                + "WHERE action = 'DEPLOYMENT_WEBHOOK_TRIGGER'", String.class))
                .containsExactly("system:DEPLOYMENT:" + id + ":SUCCESS");
        assertThat(jdbc.queryForObject("SELECT user_id FROM audit_log WHERE action = 'DEPLOYMENT_WEBHOOK_TRIGGER'",
                UUID.class)).isNull();
        assertThat(jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a", String.class))
                .noneMatch(row -> row.contains(token) || row.contains(token.substring(4)));
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid", String.class, id))
                .isEqualTo("RUNNING");
    }

    @Test
    void triggerOfANeverDeployedDeploymentIs400AndLeavesItTriggerableLater() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");
        String token = enable(op, id);

        MvcResult r = trigger(token, freshAddress());

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.code")).isEqualTo("NOT_DEPLOYED");
        assertThat(audit()).contains("DEPLOYMENT_WEBHOOK_TRIGGER:FAILURE");
        mvc.perform(op.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
    }

    @Test
    void unknownDisabledAndRotatedTokensAreIndistinguishable() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String old = enable(op, id);
        mvc.perform(op.write(post(url() + "/" + id + "/webhook/rotate"))).andExpect(status().isOk());

        MvcResult unknown = trigger("shw_" + "A".repeat(43), freshAddress());
        MvcResult rotated = trigger(old, freshAddress());
        mvc.perform(op.write(delete(url() + "/" + id + "/webhook"))).andExpect(status().isNoContent());
        MvcResult disabled = trigger(old, freshAddress());
        MvcResult huge = trigger("x".repeat(5000), freshAddress());

        for (MvcResult r : List.of(unknown, rotated, disabled, huge)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        }
        assertThat((String) JsonPath.read(unknown.getResponse().getContentAsString(), "$.code")).isEqualTo("WEBHOOK_NOT_FOUND");
        assertThat(commands).isEmpty();
    }

    @Test
    void rotateInvalidatesTheOldTokenAndIssuesANewOne() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String old = enable(op, id);

        String body = mvc.perform(op.write(post(url() + "/" + id + "/webhook/rotate")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rotatedAt").exists())
                .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(body, "$.token");

        assertThat(fresh).isNotEqualTo(old);
        assertThat(trigger(old, freshAddress()).getResponse().getStatus()).isEqualTo(404);
        assertThat(trigger(fresh, freshAddress()).getResponse().getStatus()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM deployment_webhooks", String.class))
                .isEqualTo(TokenCodec.sha256Hex(fresh));
        awaitResults(2);
        assertThat(audit()).contains("DEPLOYMENT_WEBHOOK_ROTATE:SUCCESS");
    }

    @Test
    void rotateWithoutAnEnabledWebhookIs404() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");

        mvc.perform(op.write(post(url() + "/" + id + "/webhook/rotate")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WEBHOOK_NOT_FOUND"));
        mvc.perform(op.write(delete(url() + "/" + id + "/webhook")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WEBHOOK_NOT_FOUND"));
    }

    @Test
    void disableStopsTheTriggerAndEnablingAgainIssuesADifferentToken() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String first = enable(op, id);

        mvc.perform(op.write(delete(url() + "/" + id + "/webhook"))).andExpect(status().isNoContent());
        assertThat(trigger(first, freshAddress()).getResponse().getStatus()).isEqualTo(404);
        mvc.perform(get(url() + "/" + id + "/webhook").session(op.session()))
                .andExpect(jsonPath("$.enabled").value(false));

        String second = enable(op, id);
        assertThat(second).isNotEqualTo(first);
        assertThat(trigger(first, freshAddress()).getResponse().getStatus()).isEqualTo(404);
        assertThat(trigger(second, freshAddress()).getResponse().getStatus()).isEqualTo(202);
        awaitResults(2);
        assertThat(audit()).contains("DEPLOYMENT_WEBHOOK_DISABLE:SUCCESS");
    }

    @Test
    void busyDeploymentIs409AndTheAttemptIsAudited() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String token = enable(op, id);
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;
        assertThat(trigger(token, freshAddress()).getResponse().getStatus()).isEqualTo(202);

        MvcResult second = trigger(token, freshAddress());

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(second.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("DEPLOYMENT_IN_PROGRESS");
        assertThat(audit()).contains("DEPLOYMENT_WEBHOOK_TRIGGER:SUCCESS", "DEPLOYMENT_WEBHOOK_TRIGGER:FAILURE");
        gate.countDown();
        awaitResults(2);
    }

    @Test
    void wrongTokensAreThrottledPerAddressEvenForTheRightTokenAfterwards() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String token = enable(op, id);
        String attacker = freshAddress();

        for (int i = 0; i < 5; i++) {
            assertThat(trigger("shw_" + UUID.randomUUID(), attacker).getResponse().getStatus()).isEqualTo(404);
        }
        MvcResult locked = trigger(token, attacker);
        MvcResult lockedWrong = trigger("shw_nope", attacker);

        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(lockedWrong.getResponse().getStatus()).isEqualTo(429);
        assertThat(lockedWrong.getResponse().getContentAsString()).isEqualTo(locked.getResponse().getContentAsString());
        assertThat(commands).isEmpty();
        assertThat(trigger(token, freshAddress()).getResponse().getStatus()).isEqualTo(202);
        awaitResults(2);
    }

    @Test
    void parallelUnknownTokensFromOneAddressEvaluateAtMostMaxAttemptsOfThem() throws Exception {
        String attacker = freshAddress();
        int requests = 40;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String guess = "shw_" + UUID.randomUUID();
                results.add(pool.submit(() -> {
                    start.await();
                    return trigger(guess, attacker).getResponse().getStatus();
                }));
            }
            start.countDown();
            int evaluated = 0;
            int locked = 0;
            for (Future<Integer> f : results) {
                int status = f.get(30, TimeUnit.SECONDS);
                if (status == 404) {
                    evaluated++;
                } else if (status == 429) {
                    locked++;
                }
            }
            assertThat(evaluated).isEqualTo(5);
            assertThat(locked).isEqualTo(requests - 5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void webhookLockoutIsAuditedOnceWithScopeAndAddressButWithoutTheTokens() throws Exception {
        String attacker = freshAddress();
        String guess = "shw_" + UUID.randomUUID();

        for (int i = 0; i < 8; i++) {
            trigger(i == 0 ? guess : "shw_" + UUID.randomUUID(), attacker);
        }

        List<String> rows = jdbc.queryForList("SELECT details::text FROM audit_log WHERE action = 'LOGIN_THROTTLED' "
                + "AND username = ?", String.class, attacker);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("WEBHOOK").contains(attacker).doesNotContain(guess);
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE action = 'LOGIN_THROTTLED' "
                + "AND username = ?", String.class, attacker)).isEqualTo("FAILURE");
    }

    @Test
    void validRequestsAreNotCountedAsFailures() throws Exception {
        Login op = login("operator");
        String id = deployed(op);
        String token = enable(op, id);
        String address = freshAddress();
        CountDownLatch gate = new CountDownLatch(1);
        upGate = gate;

        assertThat(trigger(token, address).getResponse().getStatus()).isEqualTo(202);
        for (int i = 0; i < 8; i++) {
            assertThat(trigger(token, address).getResponse().getStatus()).isEqualTo(409);
        }
        gate.countDown();
        awaitResults(2);
    }

    @Test
    void csrfExemptionCoversOnlyTheTriggerPath() throws Exception {
        mvc.perform(post("/api/webhooks/deploy-x/abc")).andExpect(status().isForbidden());
        mvc.perform(post("/api/webhooks/other")).andExpect(status().isForbidden());
        mvc.perform(post("/api/hosts")).andExpect(status().isForbidden());
        mvc.perform(post(TRIGGER + "abc/extra")).andExpect(status().isForbidden());
        mvc.perform(get(TRIGGER + "abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingTheDeploymentRemovesItsWebhook() throws Exception {
        Login op = login("operator");
        String id = create(op, "web");
        String token = enable(op, id);

        mvc.perform(op.write(delete(url() + "/" + id))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_webhooks", Integer.class)).isZero();
        assertThat(trigger(token, freshAddress()).getResponse().getStatus()).isEqualTo(404);
    }
}
