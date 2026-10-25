package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.host.ImageUpdates;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The image auto-update tick with a fixed clock, stubbed compose (see {@link DeploymentTestBase}), stubbed container and
 * image listings and a stubbed update check result. Real Postgres, real {@link DeploymentRunService}.
 */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class ImageAutoUpdateSchedulerTest extends DeploymentTestBase {

    private static final String KEY = "docker.io/library/nginx:latest";
    private static final String OLD_DIGEST = "nginx@sha256:" + "a".repeat(64);

    @MockitoBean
    ComposeOutputStreamService streams;
    @Autowired
    DeploymentRepository deploymentRepository;
    @Autowired
    DeploymentService deploymentService;
    @Autowired
    DeploymentRunService runs;
    @Autowired
    DeploymentLocks locks;

    private final ContainerService containers = mock(ContainerService.class);
    private final ImageService images = mock(ImageService.class);
    private final StubUpdates updates = new StubUpdates();
    private MutableClock clock;
    private ImageAutoUpdateScheduler scheduler;
    private volatile String health = "healthy";
    private volatile String state = "running";
    private volatile Runnable duringWait;
    private final AtomicInteger listFailures = new AtomicInteger();

    static final class StubUpdates implements ImageUpdates {
        final Set<String> keys = new HashSet<>();
        volatile Instant checkedAt;

        @Override
        public boolean hasUpdate(UUID hostId, String key) {
            return keys.contains(key);
        }

        @Override
        public Instant checkedAt(UUID hostId) {
            return checkedAt;
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notifications");
        clock = new MutableClock(Instant.parse("2026-03-10T03:00:00Z"));
        updates.keys.clear();
        updates.keys.add(KEY);
        updates.checkedAt = clock.instant().minusSeconds(600);
        health = "healthy";
        state = "running";
        listFailures.set(0);
        when(streams.start(any())).thenReturn((Consumer<String>) line -> { });
        when(containers.listContainers(any(), any())).thenAnswer(inv -> {
            if (listFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, "down", null);
            }
            return jdbc.queryForList("SELECT name FROM deployments", String.class).stream()
                    .map(n -> new ContainerDto("c-" + n, "/" + n + "-web-1", "nginx:latest", state, "Up", health,
                            clock.instant(), List.of(), n, "web"))
                    .toList();
        });
        when(images.list(any(), any())).thenReturn(List.of(new ImageDto("sha256:img", List.of("nginx:latest"), 1L,
                clock.instant(), true, List.of(OLD_DIGEST), false)));
        scheduler = new ImageAutoUpdateScheduler(deploymentRepository, updates, containers, images, deploymentService,
                runs, locks, clock, new ImageAutoUpdateProperties(true, Duration.ofSeconds(60), Duration.ofMinutes(2),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(1)),
                ms -> {
                    clock.advance(Duration.ofMillis(ms));
                    Runnable hook = duringWait;
                    duringWait = null;
                    if (hook != null) {
                        hook.run();
                    }
                });
    }

    private UUID deployed(String name, boolean autoUpdate) throws Exception {
        Login op = login("operator");
        String body = mvc.perform(op.write(post(url())).contentType(APPLICATION_JSON)
                        .content(createJson(name, YAML, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'",
                Integer.class);
        mvc.perform(op.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'",
                        Integer.class)).isEqualTo(before + 1));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String.class, id))
                        .isEqualTo("RUNNING"));
        if (autoUpdate) {
            jdbc.update("UPDATE deployments SET image_auto_update = TRUE, image_update_window_start = '02:00', "
                    + "image_update_window_end = '05:00' WHERE id = ?", id);
        }
        commands.clear();
        composeFiles.clear();
        return id;
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT username || ':' || outcome FROM audit_log WHERE action = ? ORDER BY occurred_at",
                String.class, action);
    }

    private List<String> notificationTypes() {
        return jdbc.queryForList("SELECT DISTINCT type FROM notifications", String.class);
    }

    @Test
    void appliesUpdateInsideWindowAsSystemWhenContainersBecomeHealthy() throws Exception {
        UUID id = deployed("web", true);
        int versionsBefore = jdbc.queryForObject("SELECT count(*) FROM deployment_versions WHERE deployment_id = ?",
                Integer.class, id);

        scheduler.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:SUCCESS");
        assertThat(audit("IMAGE_AUTO_UPDATE_RESULT")).containsExactly("system:SUCCESS");
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_APPLIED");
        assertThat(jdbc.queryForObject("SELECT image_update_last_result FROM deployments WHERE id = ?", String.class, id))
                .isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String.class, id))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions WHERE deployment_id = ?", Integer.class, id))
                .isEqualTo(versionsBefore); // pull-update records no new version
    }

    @Test
    void containerWithoutHealthcheckCountsAsHealthyAfterRunningForTheStablePeriod() throws Exception {
        deployed("web", true);
        health = null;
        Instant before = clock.instant();

        scheduler.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_APPLIED");
        assertThat(Duration.between(before, clock.instant())).isGreaterThanOrEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void doesNothingOutsideTheWindow() throws Exception {
        UUID id = deployed("web", true);
        clock.set(Instant.parse("2026-03-10T05:00:00Z"));

        scheduler.tick();

        assertThat(commands).isEmpty();
        assertThat(jdbc.queryForObject("SELECT image_update_last_attempt_at FROM deployments WHERE id = ?",
                Instant.class, id)).isNull();
        assertThat(audit("IMAGE_AUTO_UPDATE")).isEmpty();
    }

    @Test
    void windowOverMidnightIsRespected() throws Exception {
        UUID id = deployed("web", true);
        jdbc.update("UPDATE deployments SET image_update_window_start = '22:00', image_update_window_end = '06:00' WHERE id = ?", id);

        clock.set(Instant.parse("2026-03-10T12:00:00Z"));
        scheduler.tick();
        assertThat(commands).isEmpty();

        clock.set(Instant.parse("2026-03-10T23:30:00Z"));
        updates.checkedAt = clock.instant().minusSeconds(60);
        scheduler.tick();
        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void deploymentWithoutWindowMayUpdateAnytime() throws Exception {
        UUID id = deployed("web", true);
        jdbc.update("UPDATE deployments SET image_update_window_start = NULL, image_update_window_end = NULL WHERE id = ?", id);
        clock.set(Instant.parse("2026-03-10T14:00:00Z"));
        updates.checkedAt = clock.instant().minusSeconds(60);

        scheduler.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void unhealthyContainersAreRolledBackToThePreviousDigestAndReported() throws Exception {
        UUID id = deployed("web", true);
        health = "unhealthy";

        scheduler.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP, ComposeCommand.UP);
        assertThat(overrideYamls).singleElement().satisfies(yaml -> {
            assertThat(yaml).contains("\"web\":").contains(OLD_DIGEST);
        });
        assertThat(Files.exists(files.deploymentDir(hostId, "web").resolve("compose.rollback.yaml"))).isFalse();
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
        assertThat(jdbc.queryForObject("SELECT details->>'rolledBack' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).isEqualTo("true");
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(jdbc.queryForObject("SELECT image_update_last_result FROM deployments WHERE id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String.class, id))
                .isEqualTo("RUNNING");
    }

    @Test
    void exitedContainerCountsAsUnhealthy() throws Exception {
        deployed("web", true);
        state = "exited";

        scheduler.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(commands).hasSize(3);
    }

    @Test
    void containerThatNeverBecomesHealthyTimesOutAndRollsBack() throws Exception {
        deployed("web", true);
        health = "starting";
        Instant before = clock.instant();

        scheduler.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(Duration.between(before, clock.instant())).isGreaterThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void failedComposeUpIsRolledBackAndReported() throws Exception {
        deployed("web", true);
        upFails = true;

        scheduler.tick();

        assertThat(notificationTypes()).contains("IMAGE_UPDATE_FAILED");
        assertThat(commands.getFirst()).isEqualTo(ComposeCommand.PULL);
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
    }

    @Test
    void failedPullIsReportedWithoutRollback() throws Exception {
        UUID id = deployed("web", true);
        pullFails = true;

        scheduler.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL);
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String.class, id))
                .isEqualTo("RUNNING"); // nothing was touched: a transient pull error must not mark it ERROR
        assertThat(overrideYamls).isEmpty();
        assertThat(notificationTypes()).contains("IMAGE_UPDATE_FAILED");
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
    }

    @Test
    void busyDeploymentIsSkippedWithoutFailureOrAttempt() throws Exception {
        UUID id = deployed("web", true);
        locks.acquire(id);
        try {
            scheduler.tick();
        } finally {
            locks.release(id);
        }

        assertThat(commands).isEmpty();
        assertThat(audit("IMAGE_AUTO_UPDATE")).isEmpty();
        assertThat(notificationTypes()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT image_update_last_attempt_at FROM deployments WHERE id = ?",
                Instant.class, id)).isNull();

        scheduler.tick(); // lock free again: picked up on the next tick
        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
    }

    @Test
    void manualDeployDuringTheHealthWaitIsRejectedSoTheRollbackCannotOverwriteIt() throws Exception {
        UUID id = deployed("web", true);
        Login op = login("operator");
        health = "starting";
        duringWait = () -> {
            try {
                health = "unhealthy";
                mvc.perform(op.write(post(url() + "/" + id + "/deploy"))).andExpect(status().isConflict());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        scheduler.tick();

        // pull, up, rollback only: the manual deploy never ran
        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP, ComposeCommand.UP);
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
    }

    private ImageAutoUpdateScheduler impatientWith(DeploymentRepository repository) {
        return new ImageAutoUpdateScheduler(repository, updates, containers, images, deploymentService, runs, locks,
                clock, new ImageAutoUpdateProperties(true, Duration.ofSeconds(60), Duration.ofMinutes(2),
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(200)),
                ms -> clock.advance(Duration.ofMillis(ms)));
    }

    private DeploymentRepository failingOn(java.util.function.Predicate<String> resultMatches) {
        DeploymentRepository repo = mock(DeploymentRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(deploymentRepository));
        org.mockito.Mockito.doAnswer(inv -> {
            String result = inv.getArgument(2);
            if (resultMatches.test(result)) {
                throw new IllegalStateException("database down");
            }
            return inv.getMethod().invoke(deploymentRepository, inv.getArguments());
        }).when(repo).recordImageUpdateAttempt(any(), any(), any());
        return repo;
    }

    @Test
    void failureBeforeTheWorkerStartsStillReleasesTheLockAndStartsNothing() throws Exception {
        UUID id = deployed("web", true);
        ImageAutoUpdateScheduler broken = impatientWith(failingOn(r -> true));

        broken.tick();
        Thread.sleep(300); // a wrongly started worker would have run its pull by now

        assertThat(commands).isEmpty(); // the attempt is recorded before the worker is started
        locks.acquire(id); // free again
        locks.release(id);
    }

    @Test
    void failureWhileTheWorkerRunsKeepsTheLockUntilTheWorkerEndsAndReleasesItExactlyOnce() throws Exception {
        UUID id = deployed("web", true);
        upGate = new java.util.concurrent.CountDownLatch(1);
        ImageAutoUpdateScheduler broken = impatientWith(failingOn("FAILED"::equals)); // the timeout report fails

        broken.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThatThrownBy(() -> locks.acquire(id)).isInstanceOf(DeploymentException.class);

        upGate.countDown();
        await().atMost(15, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            locks.acquire(id); // a manual run takes the lock as soon as the worker ended
            return true;
        });
        Thread.sleep(500); // no stray second release may drop the manual run's lock
        assertThatThrownBy(() -> locks.acquire(id)).isInstanceOf(DeploymentException.class);
        locks.release(id);
    }

    @Test
    void workerThatOutlivesTheWaitKeepsTheLockAndIsNeitherRolledBackNorBlocksTheOthers() throws Exception {
        UUID one = deployed("one", true);
        UUID two = deployed("two", true);
        upGate = new java.util.concurrent.CountDownLatch(1); // every compose up hangs until opened
        ImageAutoUpdateScheduler impatient = new ImageAutoUpdateScheduler(deploymentRepository, updates, containers,
                images, deploymentService, runs, locks, clock, new ImageAutoUpdateProperties(true, Duration.ofSeconds(60),
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(200)),
                ms -> clock.advance(Duration.ofMillis(ms)));
        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                // test ends
            }
            upGate.countDown();
        });
        opener.start();

        impatient.tick();

        // both deployments were tried (failure of one does not stop the other), none was rolled back concurrently
        assertThat(commands.stream().filter(c -> c == ComposeCommand.PULL)).hasSize(2);
        assertThat(overrideYamls).isEmpty();
        // the workers are still running compose, so the deployments stay locked: no manual run may start meanwhile
        for (UUID id : List.of(one, two)) {
            assertThatThrownBy(() -> locks.acquire(id)).isInstanceOf(DeploymentException.class);
        }
        assertThat(jdbc.queryForList("SELECT details->>'rolledBack' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).containsOnly("false");
        // once the workers end, the locks are released again
        await().atMost(15, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
            for (UUID id : List.of(one, two)) {
                locks.acquire(id);
                locks.release(id);
            }
        });
    }

    @Test
    void rollbackOverrideQuotesServiceNames() throws Exception {
        deployed("web", true);
        health = "unhealthy";

        scheduler.tick();

        assertThat(overrideYamls).singleElement().satisfies(y -> assertThat(y).contains("\"web\":"));
    }

    @Test
    void failureOfOneDeploymentDoesNotStopTheOthers() throws Exception {
        deployed("one", true);
        deployed("two", true);
        listFailures.set(1); // the first deployment's container listing fails

        scheduler.tick();

        assertThat(commands.stream().filter(c -> c == ComposeCommand.PULL)).hasSize(1);
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_APPLIED");
    }

    @Test
    void sameUpdateCheckIsAttemptedOnlyOnceEvenAfterAFailure() throws Exception {
        deployed("web", true);
        health = "unhealthy";
        scheduler.tick();
        int afterFirst = commands.size();

        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();
        scheduler.tick();
        assertThat(commands).hasSize(afterFirst);
        assertThat(audit("IMAGE_AUTO_UPDATE")).hasSize(1);

        updates.checkedAt = clock.instant().plusSeconds(1); // a newer check result allows a new attempt
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();
        assertThat(commands.size()).isGreaterThan(afterFirst);
        assertThat(audit("IMAGE_AUTO_UPDATE")).hasSize(2);
    }

    @Test
    void successfulUpdateIsNotRepeatedOnTheNextTick() throws Exception {
        deployed("web", true);
        scheduler.tick();
        int afterFirst = commands.size();

        scheduler.tick();

        assertThat(commands).hasSize(afterFirst);
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_APPLIED");
    }

    @Test
    void skipsDisabledGitStoppedAndUpToDateDeployments() throws Exception {
        UUID disabled = deployed("off", false);
        UUID stopped = deployed("stopped", true);
        jdbc.update("UPDATE deployments SET status = 'STOPPED' WHERE id = ?", stopped);
        UUID git = deployed("gitd", true);
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', "
                + "git_ref = 'main', git_compose_path = 'compose.yaml' WHERE id = ?", git);

        scheduler.tick();
        assertThat(commands).isEmpty();

        jdbc.update("UPDATE deployments SET source_type = 'FILE', git_repo_url = NULL, git_ref = NULL, "
                + "git_compose_path = NULL WHERE id = ?", git);
        updates.keys.clear();
        scheduler.tick();
        assertThat(commands).isEmpty();
        assertThat(disabled).isNotNull();
    }

    @Test
    void noCheckResultYetMeansNoUpdate() throws Exception {
        deployed("web", true);
        updates.checkedAt = null;

        scheduler.tick();

        assertThat(commands).isEmpty();
    }

    @Test
    void rollbackWithoutKnownPreviousDigestIsReportedAsNotRolledBack() throws Exception {
        deployed("web", true);
        when(images.list(any(), any())).thenReturn(List.of());
        health = "unhealthy";

        scheduler.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThat(jdbc.queryForObject("SELECT details->>'rolledBack' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).isEqualTo("false");
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
    }

    @Test
    void auditAndNotificationsContainNoSecrets() throws Exception {
        deployed("web", true);
        health = "unhealthy";

        scheduler.tick();

        String audit = String.join(" ", jdbc.queryForList("SELECT details::text FROM audit_log WHERE action LIKE 'IMAGE_AUTO_UPDATE%'",
                String.class));
        String messages = String.join(" ", jdbc.queryForList("SELECT message FROM notifications", String.class));
        assertThat(audit + messages).doesNotContain("sha256:").doesNotContain("PASSWORD");
    }
}
