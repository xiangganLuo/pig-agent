package io.pigagent.session;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentModelSwitcher;
import io.pigagent.core.memory.CompositeLongTermMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates the session lifecycle. The agent is read through an {@link AgentHolder} so that
 * a runtime model switch (which rebuilds the agent) is transparent here. When a session is
 * activated, {@link AgentModelSwitcher#ensureModel(String)} makes the agent use that session's
 * bound model (or the global default), satisfying per-session temporary switching.
 *
 * <p><b>AgentScope 2.0 conversation state (av2 Phase 3).</b> The 1.x
 * {@code io.agentscope.core.session.Session}/{@code JsonSession} classes are removed; conversation
 * history now persists automatically through the native {@code AgentStateStore}, keyed by
 * {@code (userId="pig", sessionId)}. This manager no longer loads/clears the agent's conversation on
 * a switch — instead the active session id flows into the agent's per-turn
 * {@code call/stream} (via {@code PigAgent.stream(Msg, sessionId)}), and the
 * store auto-loads/saves the correct slot per turn. This manager keeps pig's differentiators as a
 * thin <em>metadata sidecar</em>: the {@link Session} record (name/timestamps/model binding/
 * compression lineage) via {@link SessionRepository}, plus the per-session temporary memory file.
 * Switching a session = save current → ensure the session's model → point the temp-memory tier at
 * the target → record the active id (the native store handles the conversation itself).
 */
public final class SessionManager {

    private static final String TEMP_MEMORY_FILE = "temp-memory.md";

    private final AgentHolder agentHolder;
    private final AgentModelSwitcher modelSwitcher;
    private final CompositeLongTermMemory memory;
    private final SessionRepository repository;
    private final ConfigurationManager configManager;
    private final Path sessionsDir;
    private final SessionMemoryFactory sessionMemoryFactory;

    private String currentSessionId;

    /** Backward-compatible constructor: uses the raw {@link SessionMemoryFactory#DEFAULT}. */
    public SessionManager(AgentHolder agentHolder,
                          AgentModelSwitcher modelSwitcher,
                          CompositeLongTermMemory memory,
                          SessionRepository repository,
                          ConfigurationManager configManager,
                          Path sessionsDir) {
        this(agentHolder, modelSwitcher, memory, repository, configManager,
                sessionsDir, SessionMemoryFactory.DEFAULT);
    }

    public SessionManager(AgentHolder agentHolder,
                          AgentModelSwitcher modelSwitcher,
                          CompositeLongTermMemory memory,
                          SessionRepository repository,
                          ConfigurationManager configManager,
                          Path sessionsDir,
                          SessionMemoryFactory sessionMemoryFactory) {
        this.agentHolder = agentHolder;
        this.modelSwitcher = modelSwitcher;
        this.memory = memory;
        this.repository = repository;
        this.configManager = configManager;
        this.sessionsDir = sessionsDir;
        this.sessionMemoryFactory = sessionMemoryFactory == null
                ? SessionMemoryFactory.DEFAULT : sessionMemoryFactory;
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

    /** Make the given session current: persist the previous conversation, ensure the right
     * model is loaded, then point the temp-memory tier at this session. The conversation itself is
     * restored automatically by the native state store on the next session-aware turn. */
    public void activate(String id) {
        if (currentSessionId != null) {
            saveCurrent();
        }
        Session target = repository.findById(id).orElse(null);
        modelSwitcher.ensureModel(target != null ? target.modelId() : null);

        // No manual clear/load: the native AgentStateStore auto-loads the (pig, id) conversation
        // slot on the next session-aware call/stream (and auto-saves it after each turn).
        memory.setSessionMemory(sessionMemoryFactory.create(tempMemoryPath(id)));
        currentSessionId = id;
        configManager.updateConfig(c -> c.setCurrentSessionId(id));
        touch(id);
    }

    /** Re-apply the current session (used after a model bind/default change): persists the
     * latest conversation, rebuilds the agent for the right model, then reloads. */
    public void reactivateCurrent() {
        if (currentSessionId != null) {
            activate(currentSessionId);
        }
    }

    /** Bind a model to the current session (temporary switch); null clears the binding. */
    public void bindCurrentSessionModel(String modelId) {
        if (currentSessionId == null) {
            return;
        }
        repository.findById(currentSessionId)
                .filter(s -> !s.corrupt())
                .ifPresent(s -> repository.save(s.withModelId(modelId)));
    }

    /** Create a blank session (no history) and switch to it. */
    public Session createBlank(String name) {
        Session created = repository.save(Session.create(name));
        activate(created.id());
        return created;
    }

    /** Fork the current session: copy its conversation + temp memory + model binding. */
    public Session fork(String name) {
        if (currentSessionId == null) {
            return createBlank(name);
        }
        saveCurrent();

        Session source = repository.findById(currentSessionId).orElse(null);
        String forkName = (name == null || name.isBlank())
                ? (source != null ? source.name() + " (fork)" : Session.DEFAULT_NAME)
                : name.strip();

        Session forked = Session.create(forkName);
        if (source != null && source.modelId() != null) {
            forked = forked.withModelId(source.modelId());
        }
        forked = repository.save(forked);
        // Copy the source session's conversation slot into the fork's slot (native state store).
        agentHolder.get().copyConversation(currentSessionId, forked.id());
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
        agentHolder.get().clearConversation(currentSessionId);
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
            currentSessionId = null; // avoid saveCurrent() re-creating the deleted directory
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

    /** Persist the current conversation and bump its last-active time (autosave per turn). The
     * native store also auto-saves per turn; this is an explicit flush of the current slot. */
    public void saveCurrent() {
        if (currentSessionId == null) {
            return;
        }
        agentHolder.get().saveTo(currentSessionId);
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

    /**
     * Query entry for a session's compression lineage (the {@code lineageId}/{@code parentSessionId}
     * carried on its metadata), for future TUI/frontend consumption. Empty when the session is
     * unknown or its metadata is unreadable (corrupt).
     */
    public Optional<Session> lineageOf(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return repository.findById(sessionId).filter(s -> !s.corrupt());
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
