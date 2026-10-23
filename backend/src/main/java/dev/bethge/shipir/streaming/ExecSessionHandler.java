package dev.bethge.shipir.streaming;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.audit.AuditLogEntry;
import dev.bethge.shipir.audit.AuditRecord;
import dev.bethge.shipir.audit.AuditService;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import dev.bethge.shipir.common.security.SecretDecryptionException;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.host.DockerHostRepository;
import dev.bethge.shipir.host.HostEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.web.session.HttpSessionIdChangedEvent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Interactive console into a container (ADMIN only). {@link #start} opens the exec and returns a session id; the
 * owner subscribes to {@code /topic/exec/{id}} (output frames, see {@link ExecMessage}) and SENDs keystrokes to
 * {@code /app/exec/{id}/input} and a size to {@code /app/exec/{id}/resize}. Output produced before the subscription
 * is buffered. The exec is closed on process exit, UNSUBSCRIBE, disconnect, logout of the HTTP session it was
 * started from, or when no input arrived for the idle timeout. Start and end are audited (EXEC_START / EXEC_END);
 * input bytes are never logged, stored or audited.
 */
@Service
@EnableConfigurationProperties(ExecProperties.class)
public class ExecSessionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecSessionHandler.class);

    static final String OUTPUT_PREFIX = "/topic/exec/";
    static final String APP_PREFIX = "/app/exec/";
    static final List<String> ALLOWED_SHELLS = List.of("/bin/sh", "/bin/bash");
    private static final Pattern CONTAINER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final int MAX_COLS = 500;
    private static final int MAX_ROWS = 200;

    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final UUID hostId;
        final String containerId;
        final String shell;
        final String username;
        final UUID userId;
        final Instant startedAt;
        volatile String httpSessionId;
        // guarded by this
        Instant lastInput;
        Instant endedAt;
        ExecHandle handle;
        String stompSessionId;
        String subscriptionId;
        long nextSeq = 1;
        final Deque<ExecMessage> buffer = new ArrayDeque<>();
        int bufferedBytes;

        Session(UUID hostId, String containerId, String shell, String username, UUID userId, String httpSessionId,
                Instant now) {
            this.hostId = hostId;
            this.containerId = containerId;
            this.shell = shell;
            this.username = username;
            this.userId = userId;
            this.httpSessionId = httpSessionId;
            this.startedAt = now;
            this.lastInput = now;
        }

        boolean ended() {
            return endedAt != null;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ExecService exec;
    private final DockerHostRepository hosts;
    private final HostEndpointResolver endpoints;
    private final UserRepository users;
    private final StompFrameSender sender;
    private final AuditService audit;
    private final ExecProperties properties;
    private final JsonMapper mapper;
    private final Clock clock;

    ExecSessionHandler(ExecService exec, DockerHostRepository hosts, HostEndpointResolver endpoints,
                       UserRepository users, StompFrameSender sender, AuditService audit,
                       ExecProperties properties, JsonMapper mapper, Clock clock) {
        this.exec = exec;
        this.hosts = hosts;
        this.endpoints = endpoints;
        this.users = users;
        this.sender = sender;
        this.audit = audit;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Opens a console and returns its session id. Only the shells in {@link #ALLOWED_SHELLS} can be started. */
    @Auditable(action = "EXEC_START", targetType = "CONTAINER", targetId = "#containerId")
    public String start(UUID hostId, String containerId, String shell, Integer cols, Integer rows,
                        String username, String httpSessionId) {
        if (containerId == null || !CONTAINER_ID.matcher(containerId).matches()) {
            throw new ExecException(HttpStatus.BAD_REQUEST, "INVALID_RESOURCE_ID", "Invalid resource id");
        }
        String chosenShell = shell == null ? ALLOWED_SHELLS.get(0) : shell;
        if (!ALLOWED_SHELLS.contains(chosenShell)) {
            throw new ExecException(HttpStatus.BAD_REQUEST, "INVALID_SHELL", "Shell must be one of " + ALLOWED_SHELLS);
        }
        int c = cols == null ? DEFAULT_COLS : cols;
        int r = rows == null ? DEFAULT_ROWS : rows;
        if (c < 1 || c > MAX_COLS || r < 1 || r > MAX_ROWS) {
            throw new ExecException(HttpStatus.BAD_REQUEST, "INVALID_SIZE",
                    "cols must be 1.." + MAX_COLS + " and rows 1.." + MAX_ROWS);
        }
        var host = hosts.findById(hostId).orElseThrow(
                () -> new ExecException(HttpStatus.NOT_FOUND, "HOST_NOT_FOUND", "Environment not found"));
        UUID userId = users.findByUsername(username).map(u -> u.getId()).orElse(null);
        Session session = new Session(hostId, containerId, chosenShell, username, userId, httpSessionId, clock.instant());
        synchronized (sessions) {
            long open = sessions.values().stream()
                    .filter(s -> s.username.equals(username) && !s.ended()).count();
            if (open >= properties.maxSessionsPerUser()) {
                throw new ExecException(HttpStatus.CONFLICT, "EXEC_LIMIT_REACHED", "Too many open consoles");
            }
            sessions.put(session.id, session);
        }
        try {
            ExecHandle handle = exec.open(hostId, endpoints.endpointOf(host), containerId, List.of(chosenShell), c, r,
                    bytes -> onOutput(session, bytes), failure -> onEnd(session, failure));
            boolean alreadyEnded;
            synchronized (session) {
                session.handle = handle;
                alreadyEnded = session.ended();
            }
            if (alreadyEnded) {
                handle.close();
            }
        } catch (DockerConnectionException e) {
            sessions.remove(session.id);
            throw switch (e.getCode()) {
                case NOT_FOUND -> new ExecException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Container not found");
                case INVALID_STATE -> new ExecException(HttpStatus.CONFLICT, "INVALID_CONTAINER_STATE",
                        "The container is not running");
                default -> new ExecException(HttpStatus.BAD_GATEWAY, e.getCode().name(), "Environment could not be reached");
            };
        } catch (EncryptionKeyMissingException e) {
            sessions.remove(session.id);
            throw new ExecException(HttpStatus.BAD_GATEWAY, "ENCRYPTION_KEY_MISSING", "No encryption key is configured");
        } catch (SecretDecryptionException e) {
            sessions.remove(session.id);
            throw new ExecException(HttpStatus.BAD_GATEWAY, "DECRYPTION_FAILED",
                    "Stored connection secrets could not be decrypted");
        } catch (RuntimeException e) {
            sessions.remove(session.id);
            throw e;
        }
        return session.id;
    }

    /** Is this the owner of the (still known) session? */
    boolean isOwner(String username, String sessionId) {
        Session s = sessions.get(sessionId);
        return s != null && s.username.equals(username);
    }

    /** Binds the output to the owner's subscription and replays buffered frames. False if refused. */
    boolean attach(String username, String sessionId, String stompSessionId, String subscriptionId) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.username.equals(username)) {
            return false;
        }
        synchronized (s) {
            if (s.stompSessionId != null) {
                return false;
            }
            s.stompSessionId = stompSessionId;
            s.subscriptionId = subscriptionId;
            for (ExecMessage m : s.buffer) {
                send(s, m);
            }
            s.buffer.clear();
            s.bufferedBytes = 0;
            if (s.ended()) {
                sessions.remove(s.id);
            }
        }
        return true;
    }

    /** Keystrokes from the owner. Returns false if the session is unknown, foreign or the frame is too large. */
    boolean input(String username, String sessionId, byte[] bytes) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.username.equals(username) || bytes == null || bytes.length > properties.maxInputBytes()) {
            return false;
        }
        ExecHandle handle;
        synchronized (s) {
            if (s.ended()) {
                return true; // late keystrokes after the end are dropped
            }
            s.lastInput = clock.instant();
            handle = s.handle;
        }
        if (handle != null) {
            handle.write(bytes);
        }
        return true;
    }

    /** Terminal size {@code {"cols":n,"rows":n}} from the owner. False if refused or malformed. */
    boolean resize(String username, String sessionId, byte[] json) {
        Session s = sessions.get(sessionId);
        if (s == null || !s.username.equals(username) || json == null || json.length > 256) {
            return false;
        }
        int cols;
        int rows;
        try {
            JsonNode node = mapper.readTree(new String(json, StandardCharsets.UTF_8));
            if (!node.path("cols").isInt() || !node.path("rows").isInt()) {
                return false;
            }
            cols = node.get("cols").asInt();
            rows = node.get("rows").asInt();
        } catch (RuntimeException e) {
            return false;
        }
        if (cols < 1 || cols > MAX_COLS || rows < 1 || rows > MAX_ROWS) {
            return false;
        }
        ExecHandle handle;
        synchronized (s) {
            if (s.ended()) {
                return true;
            }
            s.lastInput = clock.instant();
            handle = s.handle;
        }
        if (handle != null) {
            handle.resize(cols, rows);
        }
        return true;
    }

    /** UNSUBSCRIBE or disconnect of the attached client ends its console. */
    void detach(String stompSessionId, String subscriptionId) {
        for (Session s : List.copyOf(sessions.values())) {
            boolean match;
            synchronized (s) {
                match = stompSessionId.equals(s.stompSessionId)
                        && (subscriptionId == null || subscriptionId.equals(s.subscriptionId));
            }
            if (match) {
                finish(s, ExecMessage.Status.ENDED, subscriptionId == null ? "DISCONNECTED" : "CLOSED");
            }
        }
    }

    // public: this bean is proxied for @Auditable, and event/scheduling callbacks only reach the target through public methods
    @EventListener
    public void onHttpSessionDestroyed(HttpSessionDestroyedEvent event) {
        String id = event.getId();
        for (Session s : List.copyOf(sessions.values())) {
            if (id.equals(s.httpSessionId)) {
                finish(s, ExecMessage.Status.ENDED, "LOGOUT");
            }
        }
    }

    @EventListener
    public void onHttpSessionIdChanged(HttpSessionIdChangedEvent event) {
        for (Session s : sessions.values()) {
            if (event.getOldSessionId().equals(s.httpSessionId)) {
                s.httpSessionId = event.getNewSessionId();
            }
        }
    }

    @Scheduled(fixedDelayString = "${shipir.streaming.exec.sweep-interval:10s}")
    public void sweep() {
        Instant now = clock.instant();
        Duration idle = properties.idleTimeout();
        for (Session s : List.copyOf(sessions.values())) {
            boolean idleOut;
            boolean abandoned;
            synchronized (s) {
                idleOut = !s.ended() && Duration.between(s.lastInput, now).compareTo(idle) > 0;
                abandoned = s.ended() && Duration.between(s.endedAt, now).compareTo(idle) > 0;
            }
            if (idleOut) {
                finish(s, ExecMessage.Status.ENDED, "IDLE_TIMEOUT");
            } else if (abandoned) {
                sessions.remove(s.id, s);
            }
        }
    }

    int openSessions() {
        return (int) sessions.values().stream().filter(s -> !s.ended()).count();
    }

    private void onOutput(Session s, byte[] bytes) {
        ExecMessage message;
        synchronized (s) {
            if (s.ended()) {
                return;
            }
            message = new ExecMessage(s.nextSeq++, ExecMessage.Type.OUTPUT,
                    Base64.getEncoder().encodeToString(bytes), null, null);
            if (s.stompSessionId != null) {
                send(s, message);
                return;
            }
            s.buffer.addLast(message);
            s.bufferedBytes += bytes.length;
            while (s.bufferedBytes > properties.maxBufferBytes() && s.buffer.size() > 1) {
                s.bufferedBytes -= Base64.getDecoder().decode(s.buffer.removeFirst().data()).length;
            }
        }
    }

    private void onEnd(Session s, Throwable failure) {
        if (failure instanceof DockerConnectionException e) {
            finish(s, ExecMessage.Status.ERROR, e.getCode().name());
        } else if (failure != null) {
            finish(s, ExecMessage.Status.ERROR, "UNAVAILABLE");
        } else {
            finish(s, ExecMessage.Status.ENDED, "EXITED");
        }
    }

    private void finish(Session s, ExecMessage.Status status, String reason) {
        ExecHandle handle;
        synchronized (s) {
            if (s.ended()) {
                return;
            }
            s.endedAt = clock.instant();
            handle = s.handle;
            ExecMessage last = new ExecMessage(s.nextSeq++, ExecMessage.Type.STATUS, null, status, reason);
            if (s.stompSessionId != null) {
                send(s, last);
                sessions.remove(s.id, s);
            } else {
                s.buffer.addLast(last); // a client that attaches late still sees how it ended
            }
        }
        if (handle != null) {
            try {
                handle.close();
            } catch (RuntimeException e) {
                log.debug("Closing exec session failed: {}", e.getClass().getSimpleName());
            }
        }
        try {
            audit.record(new AuditRecord(s.userId, s.username, "EXEC_END", AuditLogEntry.Outcome.SUCCESS,
                    "CONTAINER", s.containerId,
                    Map.of("reason", reason, "hostId", s.hostId.toString(), "shell", s.shell,
                            "durationSeconds", Duration.between(s.startedAt, s.endedAt).toSeconds())));
        } catch (RuntimeException e) {
            log.warn("Recording EXEC_END failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Caller holds the session lock. */
    private void send(Session s, ExecMessage message) {
        sender.send(s.stompSessionId, s.subscriptionId, OUTPUT_PREFIX + s.id, message);
    }
}
