package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.github.dockerjava.api.model.EventType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.DockerProperties;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerEventServiceTest {

    private static Event event(EventType type, String action, String id, Map<String, String> attrs) {
        return new Event().withType(type).withAction(action)
                .withEventActor(new EventActor().withId(id).withAttributes(attrs))
                .withTimenano(1_700_000_000_123_000_000L);
    }

    @Test
    void mapsExitCodeOfDieEvent() {
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "die", "abc",
                Map.of("name", "web", "exitCode", "137"))).exitCode()).isEqualTo(137);
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "die", "abc",
                Map.of("exitCode", "oops"))).exitCode()).isNull();
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "start", "abc", Map.of())).exitCode()).isNull();
    }

    @Test
    void mapsContainerEventWithNameAndComposeProject() {
        DockerEventDto dto = DockerEventServiceImpl.map(event(EventType.CONTAINER, "start", "abc",
                Map.of("name", "web-1", "com.docker.compose.project", "shop")));
        assertThat(dto.type()).isEqualTo(DockerEventDto.Type.CONTAINER);
        assertThat(dto.action()).isEqualTo("start");
        assertThat(dto.id()).isEqualTo("abc");
        assertThat(dto.name()).isEqualTo("web-1");
        assertThat(dto.composeProject()).isEqualTo("shop");
        assertThat(dto.time().getEpochSecond()).isEqualTo(1_700_000_000L);
    }

    @Test
    void mapsOtherResourceTypesWithoutOptionalFields() {
        assertThat(DockerEventServiceImpl.map(event(EventType.VOLUME, "create", "v1", null)))
                .satisfies(d -> {
                    assertThat(d.type()).isEqualTo(DockerEventDto.Type.VOLUME);
                    assertThat(d.name()).isNull();
                    assertThat(d.composeProject()).isNull();
                });
        assertThat(DockerEventServiceImpl.map(event(EventType.NETWORK, "connect", "n1", Map.of())).type())
                .isEqualTo(DockerEventDto.Type.NETWORK);
        assertThat(DockerEventServiceImpl.map(event(EventType.IMAGE, "pull", "i1", Map.of())).type())
                .isEqualTo(DockerEventDto.Type.IMAGE);
    }

    @Test
    void dropsNoiseAndForeignTypes() {
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "exec_start: sh", "a", Map.of()))).isNull();
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "exec_die", "a", Map.of()))).isNull();
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "attach", "a", Map.of()))).isNull();
        assertThat(DockerEventServiceImpl.map(event(EventType.CONTAINER, "resize", "a", Map.of()))).isNull();
        assertThat(DockerEventServiceImpl.map(event(EventType.DAEMON, "reload", "a", Map.of()))).isNull();
        assertThat(DockerEventServiceImpl.map(new Event())).isNull();
    }

    @Test
    void callbackForwardsMappedEventsAndEndsOnce() throws Exception {
        List<DockerEventDto> got = new ArrayList<>();
        List<Throwable> ends = new ArrayList<>();
        var cb = new DockerEventServiceImpl.EventCallback(got::add, ends::add, t -> t);
        cb.onNext(event(EventType.CONTAINER, "die", "a", Map.of()));
        cb.onNext(event(EventType.CONTAINER, "exec_create", "a", Map.of()));
        cb.onError(new RuntimeException("boom"));
        cb.onComplete();
        cb.close();
        assertThat(got).extracting(DockerEventDto::action).containsExactly("die");
        assertThat(ends).hasSize(1);
        assertThat(ends.get(0)).hasMessage("boom");
    }

    @Test
    void closeReportsNormalEnd() throws Exception {
        List<Throwable> ends = new ArrayList<>();
        var cb = new DockerEventServiceImpl.EventCallback(e -> { }, ends::add, t -> t);
        cb.close();
        cb.close();
        assertThat(ends).containsExactly((Throwable) null);
    }

    @Test
    void failureIsTranslatedToConnectionCode() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        DockerHostConnectionManager manager = new DockerHostConnectionManager(new DockerProperties(
                "unix://" + Path.of("/nonexistent/shipir.sock"), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        var calls = new DockerCallSupport(manager);
        var cb = new DockerEventServiceImpl.EventCallback(e -> { }, seen::set, t -> calls.translate((Exception) t));
        cb.onError(new SocketTimeoutException("slow"));
        assertThat(seen.get()).isInstanceOfSatisfying(DockerConnectionException.class,
                e -> assertThat(e.getCode()).isEqualTo(DockerErrorCode.TIMEOUT));
    }

    @Test
    void unreachableDaemonFailsStreamCallSynchronously() {
        DockerHostConnectionManager manager = new DockerHostConnectionManager(new DockerProperties(
                "unix:///nonexistent/shipir.sock", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        var service = new DockerEventServiceImpl(manager);
        UUID host = UUID.randomUUID();
        DockerEndpoint endpoint = manager.localEndpoint();
        assertThatThrownBy(() -> service.stream(host, endpoint, e -> { }))
                .isInstanceOfSatisfying(DockerConnectionException.class,
                        e -> assertThat(e.getCode()).isIn(DockerErrorCode.UNREACHABLE, DockerErrorCode.TIMEOUT));
    }
}
