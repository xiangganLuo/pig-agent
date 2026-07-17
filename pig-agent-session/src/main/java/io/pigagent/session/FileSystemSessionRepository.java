package io.pigagent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Stores one {@code meta.json} per session directory under {@code sessions/{id}/}.
 *
 * <p>Timestamps are persisted as epoch milliseconds so plain Jackson can round-trip them
 * without the JSR-310 module. {@link #findAll()} reads each session independently and turns
 * any unreadable entry into a {@code corrupt} {@link Session} rather than failing the whole
 * listing (fault tolerance per the requirements).
 *
 * <p>This is the <em>metadata sidecar</em> only. In AgentScope 2.0 the actual conversation state
 * lives in the native {@code AgentStateStore} under {@code workspace/state/pig/{id}/}, NOT in this
 * {@code sessions/{id}/} directory (which holds {@code meta.json} + the legacy {@code temp-memory.md}).
 * {@link #deleteById(String)} therefore removes only the sidecar; the native conversation slot is
 * deleted separately by {@code SessionManager.delete} via {@code PigAgent.deleteConversation}.
 */
public final class FileSystemSessionRepository implements SessionRepository {

    private static final String META_FILE = "meta.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path sessionsDir;

    public FileSystemSessionRepository(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    @Override
    public Session save(Session session) {
        try {
            Path dir = sessionsDir.resolve(session.id());
            Files.createDirectories(dir);
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(dir.resolve(META_FILE).toFile(), Meta.from(session));
            return session;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session: " + session.id(), e);
        }
    }

    @Override
    public Optional<Session> findById(String id) {
        Path meta = sessionsDir.resolve(id).resolve(META_FILE);
        if (!Files.exists(meta)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(meta.toFile(), Meta.class).toSession());
        } catch (IOException e) {
            return Optional.of(corrupt(id));
        }
    }

    @Override
    public List<Session> findAll() {
        List<Session> sessions = new ArrayList<>();
        if (!Files.isDirectory(sessionsDir)) {
            return sessions;
        }
        try (Stream<Path> dirs = Files.list(sessionsDir)) {
            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(dir -> {
                        Path meta = dir.resolve(META_FILE);
                        if (!Files.exists(meta)) {
                            return; // a session dir without metadata is not yet a session
                        }
                        try {
                            sessions.add(MAPPER.readValue(meta.toFile(), Meta.class).toSession());
                        } catch (IOException e) {
                            sessions.add(corrupt(dir.getFileName().toString()));
                        }
                    });
        } catch (IOException ignored) {
            // Listing failed entirely; return whatever we have (possibly empty).
        }
        return sessions;
    }

    @Override
    public void deleteById(String id) {
        Path dir = sessionsDir.resolve(id);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static Session corrupt(String id) {
        Instant now = Instant.now();
        return new Session(id, "(corrupt)", now, now, null, null, null, true);
    }

    /**
     * On-disk shape of session metadata. Epoch-millis timestamps keep Jackson plain.
     *
     * <p>{@code lineageId}/{@code parentSessionId} are the compression-lineage fields; they are
     * absent from files written before this capability, in which case Jackson leaves them
     * {@code null} (read back as "no parent") — the fault-tolerant behaviour required of the
     * repository.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Meta {
        public String id;
        public String name;
        public long createdAt;
        public long lastActiveAt;
        public String modelId;
        public String lineageId;
        public String parentSessionId;

        static Meta from(Session s) {
            Meta m = new Meta();
            m.id = s.id();
            m.name = s.name();
            m.createdAt = s.createdAt().toEpochMilli();
            m.lastActiveAt = s.lastActiveAt().toEpochMilli();
            m.modelId = s.modelId();
            m.lineageId = s.lineageId();
            m.parentSessionId = s.parentSessionId();
            return m;
        }

        Session toSession() {
            return new Session(id, name, Instant.ofEpochMilli(createdAt),
                    Instant.ofEpochMilli(lastActiveAt), modelId, lineageId, parentSessionId, false);
        }
    }
}
