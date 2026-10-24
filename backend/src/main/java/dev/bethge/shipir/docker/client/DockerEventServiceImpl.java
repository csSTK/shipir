package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerHostConnectionManager;
import dev.bethge.shipir.docker.dto.DockerEventDto;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class DockerEventServiceImpl implements DockerEventService {

    static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";

    private final DockerCallSupport calls;

    public DockerEventServiceImpl(DockerHostConnectionManager manager) {
        this.calls = new DockerCallSupport(manager);
    }

    @Override
    public Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent) {
        return stream(hostId, endpoint, onEvent, error -> { });
    }

    @Override
    public Closeable stream(UUID hostId, DockerEndpoint endpoint, Consumer<DockerEventDto> onEvent,
                            Consumer<Throwable> onEnd) {
        // the events call is asynchronous and would hide an unreachable daemon, so reach it first
        calls.call(hostId, endpoint, client -> client.pingCmd().exec());
        EventCallback callback = new EventCallback(onEvent, onEnd, error -> calls.translate(
                error instanceof Exception e ? e : new RuntimeException(error)));
        return calls.call(hostId, endpoint, client -> client.eventsCmd()
                .withEventTypeFilter(EventType.CONTAINER, EventType.IMAGE, EventType.NETWORK, EventType.VOLUME)
                .exec(callback));
    }

    /** Maps docker-java events to {@link DockerEventDto}; null for event types and actions we do not forward. */
    static DockerEventDto map(Event event) {
        if (event.getType() == null || event.getAction() == null) {
            return null;
        }
        DockerEventDto.Type type = switch (event.getType()) {
            case CONTAINER -> DockerEventDto.Type.CONTAINER;
            case IMAGE -> DockerEventDto.Type.IMAGE;
            case NETWORK -> DockerEventDto.Type.NETWORK;
            case VOLUME -> DockerEventDto.Type.VOLUME;
            default -> null;
        };
        String action = event.getAction();
        if (type == null || action.startsWith("exec_") || action.startsWith("attach") || action.startsWith("resize")) {
            return null;
        }
        Map<String, String> attributes = event.getActor() == null ? null : event.getActor().getAttributes();
        String id = event.getActor() != null && event.getActor().getId() != null ? event.getActor().getId() : event.getId();
        Instant time = event.getTimeNano() != null
                ? Instant.ofEpochSecond(0, event.getTimeNano())
                : event.getTime() != null ? Instant.ofEpochSecond(event.getTime()) : Instant.now();
        return new DockerEventDto(type, action, id, attributes == null ? null : attributes.get("name"),
                attributes == null ? null : attributes.get(COMPOSE_PROJECT_LABEL), time);
    }

    static final class EventCallback extends ResultCallback.Adapter<Event> {

        private final Consumer<DockerEventDto> onEvent;
        private final Consumer<Throwable> onEnd;
        private final java.util.function.Function<Throwable, Throwable> translator;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile boolean closed;

        EventCallback(Consumer<DockerEventDto> onEvent, Consumer<Throwable> onEnd,
                      java.util.function.Function<Throwable, Throwable> translator) {
            this.onEvent = onEvent;
            this.onEnd = onEnd;
            this.translator = translator;
        }

        @Override
        public void onNext(Event event) {
            if (closed) {
                return;
            }
            DockerEventDto dto = map(event);
            if (dto != null) {
                onEvent.accept(dto);
            }
        }

        private void finish(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                onEnd.accept(error == null ? null : translator.apply(error));
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // before super: it calls close(), which would report a normal end
            finish(throwable);
            super.onError(throwable);
        }

        @Override
        public void onComplete() {
            finish(null);
            super.onComplete();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            try {
                super.close();
            } finally {
                finish(null);
            }
        }
    }
}
