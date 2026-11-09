package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.model.Container;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction;
import dev.bethge.shipir.docker.dto.RecreateLeftoverAction.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Leftover reconciliation of an interrupted recreate, against a stubbed daemon. */
class ContainerRecreateLeftoverTest {

    private static final String OLD = "web-shipir-old-1a2b3c4d";
    private static final String OLD_RUNNING = OLD + "-r";

    private final DockerHostConnectionManager manager = mock(DockerHostConnectionManager.class);
    private final DockerClient client = mock(DockerClient.class, Answers.RETURNS_DEEP_STUBS);
    private final DockerEndpoint endpoint = new DockerEndpoint.Local("unix:///x.sock");
    private ContainerServiceImpl service;

    @BeforeEach
    void setUp() {
        when(manager.readTimeout()).thenReturn(Duration.ofSeconds(2));
        when(manager.client(any(), any())).thenReturn(client);
        service = new ContainerServiceImpl(manager);
    }

    private static Container container(String id, String name) {
        return container(id, name, "exited");
    }

    private static Container container(String id, String name, String state) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn(id);
        when(c.getNames()).thenReturn(new String[]{"/" + name});
        when(c.getState()).thenReturn(state);
        return c;
    }

    private void listing(Container... containers) {
        when(client.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of(containers));
    }

    /** What the daemon answers when asked, right before acting, which containers carry the original name. */
    private void holders(String name, Container... containers) {
        when(client.listContainersCmd().withShowAll(true).withNameFilter(List.of(name)).exec())
                .thenReturn(List.of(containers));
    }

    @Test
    void removesAStoppedLeftoverWhenTheOriginalNameExists() {
        listing(container("old1", OLD), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));

        List<RecreateLeftoverAction> actions = service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint);

        assertThat(actions).containsExactly(new RecreateLeftoverAction("web", OLD, Outcome.REMOVED));
        verify(client.removeContainerCmd("old1").withForce(true)).withRemoveVolumes(false);
        verify(client, never()).renameContainerCmd(any());
        verify(client, never()).startContainerCmd(any());
    }

    @Test
    void removesARunningLeftoverWithoutStartingAnythingWhenTheOriginalIsAlreadyRunning() {
        listing(container("old1", OLD_RUNNING), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD_RUNNING, Outcome.REMOVED));
        verify(client, never()).startContainerCmd(any());
    }

    @Test
    void startsTheOriginalNameHolderBeforeRemovingARunningLeftover() {
        listing(container("old1", OLD_RUNNING), container("cur1", "web", "created"));
        holders("web", container("cur1", "web", "created"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD_RUNNING, Outcome.ORIGINAL_STARTED));
        var order = org.mockito.Mockito.inOrder(client);
        order.verify(client).startContainerCmd("cur1");
        order.verify(client).removeContainerCmd("old1");
    }

    @Test
    void doesNotRemoveTheLeftoverWhenStartingTheOriginalFails() {
        listing(container("old1", OLD_RUNNING), container("cur1", "web", "created"));
        holders("web", container("cur1", "web", "created"));
        when(client.startContainerCmd("cur1").exec()).thenThrow(new InternalServerErrorException("boom"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD_RUNNING, Outcome.FAILED));
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void renamesARunningLeftoverBackAndStartsIt() {
        listing(container("old1", OLD_RUNNING));
        holders("web");

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD_RUNNING, Outcome.RESTORED_AND_STARTED));
        verify(client.renameContainerCmd("old1")).withName("web");
        verify(client).startContainerCmd("old1");
    }

    @Test
    void renamesAStoppedLeftoverBackWithoutStartingItWhateverItsRestartPolicy() {
        listing(container("old1", OLD));
        holders("web");

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD, Outcome.RESTORED));
        verify(client.renameContainerCmd("old1")).withName("web");
        verify(client, never()).startContainerCmd(any());
        verify(client, never()).inspectContainerCmd(any());
    }

    @Test
    void anOriginalNameTakenSinceTheListingMakesTheLeftoverBeRemovedNotRenamed() {
        listing(container("old1", OLD));
        holders("web", container("cur1", "web", "running")); // appeared after the first listing

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD, Outcome.REMOVED));
        verify(client, never()).renameContainerCmd(any());
    }

    @Test
    void aNameConflictOnRenameBackIsSkippedNotFailed() {
        listing(container("old1", OLD_RUNNING));
        holders("web");
        when(client.renameContainerCmd("old1").withName("web").exec()).thenThrow(new ConflictException("name in use"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD_RUNNING, Outcome.SKIPPED));
        verify(client, never()).startContainerCmd(any());
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void aLeftoverOfARecreateRunningInThisProcessIsLeftAlone() {
        listing(container("old1", OLD_RUNNING));
        holders("web");
        service.recreatesInFlight.add(OLD_RUNNING);

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint)).isEmpty();
        verify(client, never()).renameContainerCmd(any());
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void ignoresContainersThatMerelyLookSimilar() {
        listing(container("a", "web-shipir-old-zzz"), container("b", "shipir-old-1a2b3c4d"),
                container("c", "web-shipir-new-zzz"), container("d", "web"),
                container("e", OLD + "-x"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint)).isEmpty();
        verify(client, never()).removeContainerCmd(any());
        verify(client, never()).renameContainerCmd(any());
    }

    @Test
    void aFailingActionIsReportedAndTheOtherLeftoversAreStillHandled() {
        String otherOld = "db-shipir-old-ffffffff";
        listing(container("old1", OLD), container("old2", otherOld), container("cur2", "db", "running"));
        holders("web");
        holders("db", container("cur2", "db", "running"));
        when(client.renameContainerCmd("old1").withName("web").exec()).thenThrow(new InternalServerErrorException("boom"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint)).containsExactlyInAnyOrder(
                new RecreateLeftoverAction("web", OLD, Outcome.FAILED),
                new RecreateLeftoverAction("db", otherOld, Outcome.REMOVED));
    }

    private static final String NEW = "web-shipir-new-1a2b3c4d";

    /** What inspecting {@code new1} right before removal says. */
    private void inspectNew1(String name, String status, java.time.Instant created) {
        var r = mock(com.github.dockerjava.api.command.InspectContainerResponse.class, Answers.RETURNS_DEEP_STUBS);
        when(r.getName()).thenReturn("/" + name);
        when(r.getState().getStatus()).thenReturn(status);
        when(r.getCreated()).thenReturn(created.toString());
        when(client.inspectContainerCmd("new1").exec()).thenReturn(r);
    }

    private static final java.time.Instant LONG_AGO = java.time.Instant.now().minus(java.time.Duration.ofHours(1));

    @Test
    void aStaleListingWhoseContainerIsNowTheLiveOriginalIsNeverRemoved() {
        listing(container("new1", NEW, "created"), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));
        inspectNew1("web", "running", LONG_AGO); // the recreate finished since the listing

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", NEW, Outcome.SKIPPED));
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void aTooYoungNewContainerIsLeftForAnotherInstancesRecreate() {
        listing(container("new1", NEW, "created"), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));
        inspectNew1(NEW, "created", java.time.Instant.now());

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", NEW, Outcome.SKIPPED));
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void aNewContainerThatWasStartedIsNotRemoved() {
        listing(container("new1", NEW, "created"), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));
        inspectNew1(NEW, "running", LONG_AGO);

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", NEW, Outcome.SKIPPED));
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void removesAStaleNewContainerWhenTheOriginalNameExists() {
        inspectNew1(NEW, "created", LONG_AGO);
        listing(container("new1", NEW, "created"), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", NEW, Outcome.NEW_REMOVED));
        verify(client.removeContainerCmd("new1").withForce(true)).withRemoveVolumes(false);
        verify(client, never()).renameContainerCmd(any());
        verify(client, never()).startContainerCmd(any());
    }

    @Test
    void keepsAStaleNewContainerWhenNoContainerCarriesTheOriginalName() {
        listing(container("new1", NEW, "created"));
        holders("web");

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", NEW, Outcome.SKIPPED));
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void anOldLeftoverIsRestoredBeforeTheStaleNewContainerIsJudged() {
        inspectNew1(NEW, "created", LONG_AGO);
        listing(container("new1", NEW, "created"), container("old1", OLD));
        List<Container> restored = List.of(container("old1", "web"));
        when(client.listContainersCmd().withShowAll(true).withNameFilter(List.of("web")).exec())
                .thenReturn(List.of(), restored);

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint)).containsExactly(
                new RecreateLeftoverAction("web", OLD, Outcome.RESTORED),
                new RecreateLeftoverAction("web", NEW, Outcome.NEW_REMOVED));
    }

    @Test
    void aNewContainerOfARecreateRunningInThisProcessIsLeftAlone() {
        listing(container("new1", NEW, "created"), container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));
        service.recreatesInFlight.add(NEW);

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint)).isEmpty();
        verify(client, never()).removeContainerCmd(any());
    }

    @Test
    void theLeftoverNameIsTheOneWithoutASlashPathNotJustTheFirst() {
        Container linked = mock(Container.class);
        when(linked.getId()).thenReturn("old1");
        when(linked.getNames()).thenReturn(new String[]{"/app/db-alias", "/" + OLD});
        when(linked.getState()).thenReturn("exited");
        listing(linked, container("cur1", "web", "running"));
        holders("web", container("cur1", "web", "running"));

        assertThat(service.reconcileRecreateLeftovers(UUID.randomUUID(), endpoint))
                .containsExactly(new RecreateLeftoverAction("web", OLD, Outcome.REMOVED));
    }
}
