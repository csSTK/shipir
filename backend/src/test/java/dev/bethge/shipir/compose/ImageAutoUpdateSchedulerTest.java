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
import dev.bethge.shipir.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.AdditionalAnswers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

        @Override
        public void markApplied(UUID hostId, java.util.Collection<String> applied) {
            keys.removeAll(applied);
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
    void updateFlagIsClearedAfterASuccessfulApplyButKeptAfterAFailure() throws Exception {
        deployed("web", true);
        health = "unhealthy";
        scheduler.tick();
        assertThat(updates.keys).containsExactly(KEY); // rolled back: the update is still pending

        jdbc.update("DELETE FROM notifications");
        health = "healthy";
        updates.checkedAt = clock.instant().plusSeconds(1);
        scheduler.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_APPLIED");
        assertThat(updates.keys).isEmpty();
    }

    @Test
    void partiallyPinnedDeploymentIsReportedAsNotFullyRolledBack() throws Exception {
        UUID id = deployed("web", true);
        health = "unhealthy";
        // two services, but only nginx has a local digest to pin back
        when(containers.listContainers(any(), any())).thenReturn(List.of(
                new ContainerDto("c-web", "/web-web-1", "nginx:latest", "running", "Up", "unhealthy", clock.instant(),
                        List.of(), "web", "web"),
                new ContainerDto("c-db", "/web-db-1", "postgres:16", "running", "Up", "unhealthy", clock.instant(),
                        List.of(), "web", "db")));

        scheduler.tick();

        assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
        assertThat(jdbc.queryForObject("SELECT details->>'rolledBack' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).isEqualTo("false");
        assertThat(jdbc.queryForObject("SELECT details->>'pinnedServices' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT details->>'services' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).isEqualTo("2");
        assertThat(overrideYamls).singleElement().satisfies(yaml ->
                assertThat(yaml).contains("\"web\":").contains(OLD_DIGEST).doesNotContain("postgres"));
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
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
        assertThat(lastResult(id)).isEqualTo("ROLLED_BACK");
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
    void composeUpThrowingIsReportedAsFailedAndNeverApplied() throws Exception {
        UUID id = deployed("web", true);
        upThrows = true;

        scheduler.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(notificationTypes()).doesNotContain("IMAGE_UPDATE_APPLIED");
        assertThat(lastResult(id)).startsWith("FAILED");
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
    }

    @Test
    void failedComposeUpIsRolledBackAndReported() throws Exception {
        UUID id = deployed("web", true);
        upFails = true;

        scheduler.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(commands.getFirst()).isEqualTo(ComposeCommand.PULL);
        // the stubbed compose up fails every time, so the rollback up fails as well
        assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
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
        assertThat(lastResult(id)).isEqualTo("FAILED"); // no rollback attempted: the pull changed nothing
        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
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

    @Test
    void startErrorBeforeTheWorkerExistsIsRecordedAsPlainFailed() throws Exception {
        UUID id = deployed("web", true);
        DeploymentRunService failing = mock(DeploymentRunService.class);
        when(failing.autoPullUpdate(any())).thenThrow(new DeploymentException(
                org.springframework.http.HttpStatus.CONFLICT, "DEPLOYMENT_BUSY", "busy"));
        ImageAutoUpdateScheduler broken = new ImageAutoUpdateScheduler(deploymentRepository, updates, containers, images,
                deploymentService, failing, locks, clock, new ImageAutoUpdateProperties(true, Duration.ofSeconds(60),
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMillis(200)),
                ms -> clock.advance(Duration.ofMillis(ms)));

        broken.tick();

        assertThat(commands).isEmpty(); // nothing was pulled or changed: no rollback is involved
        assertThat(lastResult(id)).isEqualTo("FAILED");
    }

    @Test
    void failedRunIsStillReportedWhenTheStepAfterTheWorkerThrows() throws Exception {
        UUID id = deployed("web", true);
        pullFails = true;
        DeploymentService flaky = mock(DeploymentService.class, AdditionalAnswers.delegatesTo(deploymentService));
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (calls.incrementAndGet() > 1) { // the host vanished while the worker ran
                throw new IllegalStateException("host deleted");
            }
            return inv.getMethod().invoke(deploymentService, inv.getArguments());
        }).when(flaky).requireHost(any());
        ImageAutoUpdateScheduler broken = new ImageAutoUpdateScheduler(deploymentRepository, updates, containers, images,
                flaky, runs, locks, clock, new ImageAutoUpdateProperties(true, Duration.ofSeconds(60),
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(1)),
                ms -> clock.advance(Duration.ofMillis(ms)));

        broken.tick();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
        locks.acquire(id); // lock released
        locks.release(id);
    }

    @Test
    void reportThatFailsMidwayIsNotRepeatedSoTheAuditRowIsNotDuplicated() throws Exception {
        UUID id = deployed("web", true);
        pullFails = true;
        DeploymentRunService flaky = mock(DeploymentRunService.class, AdditionalAnswers.delegatesTo(runs));
        org.mockito.Mockito.doThrow(new IllegalStateException("publish failed")).when(flaky)
                .publishImageUpdate(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());

        schedulerWith(flaky).tick();

        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
        locks.acquire(id); // lock released
        locks.release(id);
    }

    private ImageAutoUpdateScheduler schedulerWith(DeploymentRunService runService) {
        return new ImageAutoUpdateScheduler(deploymentRepository, updates, containers, images, deploymentService,
                runService, locks, clock, new ImageAutoUpdateProperties(true, Duration.ofSeconds(60),
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofMinutes(1)),
                ms -> clock.advance(Duration.ofMillis(ms)));
    }

    @Test
    void interruptedWaitWhileTheWorkerRunsReportsItsLaterFailureAndKeepsTheLockUntilThen() throws Exception {
        UUID id = deployed("web", true);
        upGate = new java.util.concurrent.CountDownLatch(1);
        upFails = true;
        DeploymentRunService interrupting = mock(DeploymentRunService.class, AdditionalAnswers.delegatesTo(runs));
        org.mockito.Mockito.doAnswer(inv -> {
            CompletableFuture<?> run = (CompletableFuture<?>) inv.getMethod().invoke(runs, inv.getArguments());
            Thread.currentThread().interrupt(); // shutdown arrives while the worker is running
            return run;
        }).when(interrupting).autoPullUpdate(any());

        schedulerWith(interrupting).tick();
        Thread.interrupted(); // clear the flag for the rest of the test

        assertThat(notificationTypes()).isEmpty(); // the worker has not failed yet
        assertThatThrownBy(() -> locks.acquire(id)).isInstanceOf(DeploymentException.class);

        upGate.countDown();
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED"));
        assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
        await().atMost(15, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            locks.acquire(id);
            return true;
        });
        locks.release(id);
    }

    @Test
    void interruptedWaitOnAnAlreadyFailedRunIsReportedAsError() throws Exception {
        UUID id = deployed("web", true);
        CompletableFuture<DeploymentRunService.PullUpdateOutcome> failedRun = new CompletableFuture<>() {
            @Override
            public DeploymentRunService.PullUpdateOutcome get(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException();
            }
        };
        failedRun.completeExceptionally(new IllegalStateException("worker crashed"));
        DeploymentRunService stub = mock(DeploymentRunService.class, AdditionalAnswers.delegatesTo(runs));
        org.mockito.Mockito.doReturn(failedRun).when(stub).autoPullUpdate(any());

        schedulerWith(stub).tick();
        Thread.interrupted();

        assertThat(notificationTypes()).containsExactly("IMAGE_UPDATE_FAILED");
        assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
        assertThat(audit("IMAGE_AUTO_UPDATE")).containsExactly("system:FAILURE");
        locks.acquire(id);
        locks.release(id);
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
        ImageAutoUpdateScheduler broken = impatientWith(failingOn("FAILED_NOT_ROLLED_BACK"::equals)); // the timeout report fails

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
            assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
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
        UUID id = deployed("web", true);
        when(images.list(any(), any())).thenReturn(List.of());
        health = "unhealthy";

        scheduler.tick();

        assertThat(commands).containsExactly(ComposeCommand.PULL, ComposeCommand.UP);
        assertThat(jdbc.queryForObject("SELECT details->>'rolledBack' FROM audit_log WHERE action = 'IMAGE_AUTO_UPDATE'",
                String.class)).isEqualTo("false");
        assertThat(lastResult(id)).isEqualTo("FAILED_NOT_ROLLED_BACK");
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

    private String lastResult(UUID id) {
        return jdbc.queryForObject("SELECT image_update_last_result FROM deployments WHERE id = ?", String.class, id);
    }
}
