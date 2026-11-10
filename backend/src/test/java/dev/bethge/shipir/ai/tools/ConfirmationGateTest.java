package dev.bethge.shipir.ai.tools;

import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.common.security.AiInvocationContext;
import dev.bethge.shipir.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The confirmation gate in {@link AiToolRegistry}: write tools run only through propose + executeConfirmed. */
class ConfirmationGateTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final String ARGS = "{\"hostId\":\"" + UUID.randomUUID() + "\"}";

    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicReference<Optional<UUID>> seenSession = new AtomicReference<>();
    private final AtomicReference<String> seenUser = new AtomicReference<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private final AiTool writeTool = new FunctionTool("do_it", "does it", Map.of("type", "object", "required",
            List.of("hostId"), "additionalProperties", false,
            "properties", Map.of("hostId", Map.of("type", "string", "format", "uuid"),
                    "env", Map.of("type", "string"))),
            Role.OPERATOR, true, (a, c) -> {
                runs.incrementAndGet();
                seenSession.set(AiInvocationContext.chatSessionId());
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                seenUser.set(auth == null ? null : auth.getName());
                return "done";
            }, v -> MAPPER.writeValueAsString(v));
    private final AiTool readTool = new FunctionTool("look", "reads", Map.of("type", "object", "properties", Map.of(),
            "required", List.of()), Role.VIEWER, false, (a, c) -> "seen", v -> MAPPER.writeValueAsString(v));
    private final AiToolRegistry registry = new AiToolRegistry(List.of(writeTool, readTool), MAPPER, 20_000, clock);

    private static ToolContext ctx(UUID user, Role role) {
        return new ToolContext(user, "user-" + role.name().toLowerCase(), role);
    }

    private PendingAction propose(String args) {
        ProposalResult p = registry.propose("do_it", args, ctx(USER, Role.OPERATOR), SESSION);
        assertThat(p.accepted()).as(String.valueOf(p.error())).isTrue();
        return p.action();
    }

    @Test
    void pendingHeadlineReflectsTheEffectiveArgumentsAndHidesEnv() {
        String host = UUID.randomUUID().toString();
        PendingAction pending = propose("{\"hostId\":\"" + host + "\",\"env\":\"SECRET=hunter2\"}");
        assertThat(pending.description()).startsWith("does it").contains(host).doesNotContain("hunter2");
    }

    @Test
    void executeRefusesWriteToolsWithoutConfirmation() {
        ToolResult result = registry.execute("do_it", ARGS, ctx(USER, Role.OPERATOR));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("CONFIRMATION_REQUIRED");
        assertThat(runs).hasValue(0);
    }

    @Test
    void proposeDoesNotRunAnythingAndConfirmationRunsExactlyOnce() {
        PendingAction pending = propose(ARGS);

        assertThat(runs).hasValue(0);
        assertThat(pending.toolName()).isEqualTo("do_it");
        assertThat(pending.token()).hasSizeGreaterThanOrEqualTo(40);

        ToolResult first = registry.executeConfirmed(pending.token(), ctx(USER, Role.OPERATOR));
        ToolResult second = registry.executeConfirmed(pending.token(), ctx(USER, Role.OPERATOR));

        assertThat(first.error()).isFalse();
        assertThat(first.content()).isEqualTo("\"done\"");
        assertThat(second.error()).isTrue();
        assertThat(second.content()).contains("UNKNOWN_CONFIRMATION");
        assertThat(runs).hasValue(1);
    }

    @Test
    void confirmedCallRunsAsTheUserWithTheAiMarkerAndLeavesNoStateBehind() {
        SecurityContextHolder.clearContext();
        PendingAction pending = propose(ARGS);

        registry.executeConfirmed(pending.token(), ctx(USER, Role.OPERATOR));

        assertThat(seenSession.get()).contains(SESSION);
        assertThat(seenUser.get()).isEqualTo("user-operator");
        assertThat(AiInvocationContext.active()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anotherUserCannotUseOrBurnTheToken() {
        PendingAction pending = propose(ARGS);
        UUID other = UUID.randomUUID();

        assertThat(registry.executeConfirmed(pending.token(), ctx(other, Role.OPERATOR)).content())
                .contains("UNKNOWN_CONFIRMATION");
        assertThat(registry.cancel(pending.token(), ctx(other, Role.OPERATOR))).isFalse();
        assertThat(runs).hasValue(0);

        assertThat(registry.executeConfirmed(pending.token(), ctx(USER, Role.OPERATOR)).error()).isFalse();
    }

    @Test
    void guessedAndNullTokensAreRejected() {
        assertThat(registry.executeConfirmed("guess", ctx(USER, Role.OPERATOR)).content()).contains("UNKNOWN_CONFIRMATION");
        assertThat(registry.executeConfirmed(null, ctx(USER, Role.OPERATOR)).content()).contains("UNKNOWN_CONFIRMATION");
    }

    @Test
    void expiredConfirmationDoesNotRun() {
        PendingAction pending = propose(ARGS);
        clock.advance(AiToolRegistry.PENDING_TTL.plusSeconds(1));

        assertThat(registry.executeConfirmed(pending.token(), ctx(USER, Role.OPERATOR)).content())
                .contains("UNKNOWN_CONFIRMATION");
        assertThat(runs).hasValue(0);
    }

    @Test
    void cancelDiscardsThePendingAction() {
        PendingAction pending = propose(ARGS);

        assertThat(registry.cancel(pending.token(), ctx(USER, Role.OPERATOR))).isTrue();
        assertThat(registry.executeConfirmed(pending.token(), ctx(USER, Role.OPERATOR)).error()).isTrue();
        assertThat(runs).hasValue(0);
    }

    @Test
    void roleIsCheckedAgainAtConfirmationTime() {
        PendingAction pending = propose(ARGS);

        ToolResult result = registry.executeConfirmed(pending.token(), ctx(USER, Role.VIEWER));

        assertThat(result.error()).isTrue();
        assertThat(runs).hasValue(0);
    }

    @Test
    void viewerCannotProposeAndInvalidOrReadCallsAreRejectedUpFront() {
        assertThat(registry.propose("do_it", ARGS, ctx(USER, Role.VIEWER), SESSION).error().content())
                .contains("UNKNOWN_TOOL");
        assertThat(registry.propose("do_it", "{}", ctx(USER, Role.OPERATOR), SESSION).error().content())
                .contains("INVALID_ARGUMENTS");
        assertThat(registry.propose("look", "{}", ctx(USER, Role.OPERATOR), SESSION).error().content())
                .contains("NOT_A_WRITE_TOOL");
        assertThat(registry.propose("nope", "{}", ctx(USER, Role.OPERATOR), SESSION).error().content())
                .contains("UNKNOWN_TOOL");
    }

    @Test
    void confirmationViewMasksEnvValues() {
        PendingAction pending = propose("{\"hostId\":\"" + UUID.randomUUID() + "\",\"env\":\"TOKEN=hunter2\"}");

        assertThat(pending.arguments().toString()).doesNotContain("hunter2");
        assertThat(pending.arguments()).containsEntry("env", "<hidden>");
    }

    @Test
    void tokensAreUnique() {
        assertThat(propose(ARGS).token()).isNotEqualTo(propose(ARGS).token());
    }

}
