package io.pigagent.session;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates the session lifecycle across four collaborators: the live {@link PigAgent}
 * (conversation memory), AgentScope's {@code Session} store (conversation persistence),
 * the {@link CompositeLongTermMemory} (per-session temp memory target), and the
 * {@link SessionRepository} (listing metadata). Configuration tracks the last active
 * session id and the global memory switch.
 *
 * <p>The agent is built once; switching sessions never rebuilds it — it saves the current
 * conversation, clears + reloads the agent's memory for the target id, and repoints the
 * temporary-memory file.
 */
public final class SessionManager {

    private static final String TEMP_MEMORY_FILE = "temp-memory.md";

    private final PigAgent agent;
    private final io.agentscope.core.session.Session agentSession;
    private final CompositeLongTermMemory memory;
    private final SessionRepository repository;
    private final ConfigurationManager configManager;
    private final Path sessionsDir;

    private String currentSessionId;

    public SessionManager(PigAgent agent,
                          io.agentscope.core.session.Session agentSession,
                          CompositeLongTermMemory memory,
                          SessionRepository repository,
                          ConfigurationManager configManager,
                          Path sessionsDir) {
        this.agent = agent;
        this.agentSession = agentSession;
        this.memory = memory;
        this.repository = repository;
        this.configManager = configManager;
        this.sessionsDir = sessionsDir;
    }

    /** Restore the last active session on startup, or create a default one. */
    public void initialize() {
        memory.setEnabled(configManager.getConfig().isMemoryEnabled());

        String configured = configManager.getConfig().getCurrentSessionId();
        List<Session> all = repository.findAll();

        Optional<Session> target = Optional.empty();
        if (configured != null) {
            target = all.stream().filter(s -> !s.corrupt() && s.id().equals(configured)).findFirst();
        }
        if (target.isEmpty()) {
            target = all.stream().filter(s -> !s.corrupt())
                    .max(Comparator.comparing(Session::lastActiveAt));
        }

        if (target.isPresent()) {
            activate(target.get().id());
        } else {
            createBlank(null);
        }
    }

    /** Make the given session current: persist the previous one, then load this one. */
    public void activate(String id) {
        if (currentSessionId != null && !currentSessionId.equals(id)) {
            saveCurrent();
        }
        agent.clearMemory();
        agent.loadIfExists(agentSession, id);
        memory.setSessionMemory(new FileSystemLongTermMemory(tempMemoryPath(id)));
        currentSessionId = id;
        configManager.updateConfig(c -> c.setCurrentSessionId(id));
        touch(id);
    }

    /** Create a blank session (no history) and switch to it. */
    public Session createBlank(String name) {
        Session created = repository.save(Session.create(name));
        activate(created.id());
        return created;
    }

    /** Fork the current session: copy its conversation + temp memory into a new session. */
    public Session fork(String name) {
        if (currentSessionId == null) {
            return createBlank(name);
        }
        saveCurrent();

        Session source = repository.findById(currentSessionId).orElse(null);
        String forkName = (name == null || name.isBlank())
                ? (source != null ? source.name() + " (fork)" : Session.DEFAULT_NAME)
                : name.strip();

        Session forked = repository.save(Session.create(forkName));
        // The live agent currently holds the source conversation; persist it under the new id.
        agent.saveTo(agentSession, forked.id());
        copyTempMemory(currentSessionId, forked.id());

        activate(forked.id());
        return forked;
    }

    /** All sessions, most-recently-active first (corrupt entries sorted last). */
    public List<Session> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Session::corrupt)
                        .thenComparing(Session::lastActiveAt, Comparator.reverseOrder()))
                .toList();
    }

    /** Clear the current conversation; optionally also wipe this session's temp memory. */
    public void clearConversation(boolean withTempMemory) {
        if (currentSessionId == null) {
            return;
        }
        agent.clearMemory();
        agent.saveTo(agentSession, currentSessionId);
        if (withTempMemory) {
            try {
                Files.deleteIfExists(tempMemoryPath(currentSessionId));
            } catch (IOException ignored) {
            }
        }
        touch(currentSessionId);
    }

    /** Delete the given sessions; if the current one is removed, activate a replacement. */
    public void delete(List<String> ids) {
        boolean currentDeleted = currentSessionId != null && ids.contains(currentSessionId);
        for (String id : ids) {
            repository.deleteById(id);
        }
        if (currentDeleted) {
            // Avoid saveCurrent() re-creating the just-deleted directory.
            currentSessionId = null;
            Optional<Session> replacement = repository.findAll().stream()
                    .filter(s -> !s.corrupt())
                    .max(Comparator.comparing(Session::lastActiveAt));
            if (replacement.isPresent()) {
                activate(replacement.get().id());
            } else {
                createBlank(null);
            }
        }
    }

    /** Rename the current session. */
    public void rename(String name) {
        if (currentSessionId == null || name == null || name.isBlank()) {
            return;
        }
        repository.findById(currentSessionId)
                .filter(s -> !s.corrupt())
                .ifPresent(s -> repository.save(s.withName(name.strip())));
    }

    /** Persist the current conversation and bump its last-active time (autosave per turn). */
    public void saveCurrent() {
        if (currentSessionId == null) {
            return;
        }
        agent.saveTo(agentSession, currentSessionId);
        touch(currentSessionId);
    }

    /** If the current session still has the default name, name it from the first message. */
    public void noteUserMessage(String text) {
        if (currentSessionId == null) {
            return;
        }
        repository.findById(currentSessionId)
                .filter(s -> !s.corrupt() && s.hasDefaultName())
                .ifPresent(s -> repository.save(s.withName(Session.deriveName(text))));
    }

    /** Toggle global memory reading/writing and persist the choice. */
    public void setMemoryEnabled(boolean enabled) {
        memory.setEnabled(enabled);
        configManager.updateConfig(c -> c.setMemoryEnabled(enabled));
    }

    public boolean isMemoryEnabled() {
        return memory.isEnabled();
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public Optional<Session> getCurrentSession() {
        return currentSessionId == null ? Optional.empty() : repository.findById(currentSessionId);
    }

    private void touch(String id) {
        repository.findById(id)
                .filter(s -> !s.corrupt())
                .ifPresent(s -> repository.save(s.withLastActiveAt(Instant.now())));
    }

    private Path tempMemoryPath(String id) {
        return sessionsDir.resolve(id).resolve(TEMP_MEMORY_FILE);
    }

    private void copyTempMemory(String fromId, String toId) {
        Path src = tempMemoryPath(fromId);
        if (!Files.exists(src)) {
            return;
        }
        Path dst = tempMemoryPath(toId);
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
