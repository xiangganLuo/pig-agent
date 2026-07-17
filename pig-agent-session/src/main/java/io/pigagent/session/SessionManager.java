package io.pigagent.session;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentModelSwitcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Coordinates the session lifecycle. The agent is read through an {@link AgentHolder} so that
 * a runtime model switch (which rebuilds the agent) is transparent here. When a session is
 * activated, {@link AgentModelSwitcher#ensureModel(String)} makes the agent use that session's
 * bound model (or the global default), satisfying per-session temporary switching.
 *
 * <p><b>AgentScope 2.0 conversation state (av2 Phase 3).</b> Conversation history persists
 * automatically through the native {@code AgentStateStore}, keyed by {@code (userId="pig",
 * sessionId)}. This manager keeps pig's differentiators as a thin <em>metadata sidecar</em>: the
 * {@link Session} record (name/timestamps/model binding/compression lineage) via
 * {@link SessionRepository}. Switching a session = save current → ensure the session's model →
 * record the active id (the native store loads/creates the conversation per turn).
 *
 * <p><b>Native two-layer memory (pa-memory-native).</b> Long-term memory is now the AgentScope 2.0
 * native workspace-level {@code MEMORY.md} + daily ledger (retired: pig's self-built
 * {@code CompositeLongTermMemory} two-tier and the per-session temp-memory tier). Durable facts are
 * workspace-level and cross-session by construction, so this manager no longer swaps a session-tier
 * memory on activation. {@code /memory on|off} maps to the top-level {@code memory-enabled} config
 * flag and triggers an agent rebuild (via {@code memoryToggleHook}) so the native memory hooks/tools
 * + MEMORY.md injection are turned on/off — off = no injection, no flush.
 */
public final class SessionManager {

    /** Legacy per-session temp-memory file (retired tier) — kept only for {@code --with-memory} cleanup. */
    private static final String TEMP_MEMORY_FILE = "temp-memory.md";

    private final AgentHolder agentHolder;
    private final AgentModelSwitcher modelSwitcher;
    private final SessionRepository repository;
    private final ConfigurationManager configManager;
    private final Path sessionsDir;
    private final Runnable memoryToggleHook;
    private final Consumer<String> snapshotResetHook;

    private String currentSessionId;

    /** Convenience constructor with no memory-toggle rebuild hook (tests). */
    public SessionManager(AgentHolder agentHolder,
                          AgentModelSwitcher modelSwitcher,
                          SessionRepository repository,
                          ConfigurationManager configManager,
                          Path sessionsDir) {
        this(agentHolder, modelSwitcher, repository, configManager, sessionsDir, null);
    }

    /**
     * @param memoryToggleHook invoked after {@code /memory on|off} updates the config flag, to rebuild
     *        the live agent(s) so the native memory hooks/tools + MEMORY.md injection reflect the new
     *        state. {@code null} = no rebuild (tests / no wiring).
     */
    public SessionManager(AgentHolder agentHolder,
                          AgentModelSwitcher modelSwitcher,
                          SessionRepository repository,
                          ConfigurationManager configManager,
                          Path sessionsDir,
                          Runnable memoryToggleHook) {
        this(agentHolder, modelSwitcher, repository, configManager, sessionsDir, memoryToggleHook, null);
    }

    /**
     * @param snapshotResetHook invoked with the session id when its conversation is cleared
     *        ({@code /session clear}), so a stateful compression snapshot for that session can be reset
     *        (wire {@code compressionService::resetSnapshot}). {@code null} = no reset (tests / no wiring).
     */
    public SessionManager(AgentHolder agentHolder,
                          AgentModelSwitcher modelSwitcher,
                          SessionRepository repository,
                          ConfigurationManager configManager,
                          Path sessionsDir,
                          Runnable memoryToggleHook,
                          Consumer<String> snapshotResetHook) {
        this.agentHolder = agentHolder;
        this.modelSwitcher = modelSwitcher;
        this.repository = repository;
        this.configManager = configManager;
        this.sessionsDir = sessionsDir;
        this.memoryToggleHook = memoryToggleHook;
        this.snapshotResetHook = snapshotResetHook;
    }

    /** Restore the last active session on startup, or create a default one. */
    public void initialize() {
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

    /** Make the given session current: persist the previous conversation, then ensure the right
     * model is loaded. The conversation itself is restored automatically by the native state store
     * on the next session-aware turn; long-term memory is workspace-level (cross-session). */
    public void activate(String id) {
        if (currentSessionId != null) {
            saveCurrent();
        }
        Session target = repository.findById(id).orElse(null);
        modelSwitcher.ensureModel(target != null ? target.modelId() : null);

        // No manual clear/load: the native AgentStateStore auto-loads the (pig, id) conversation
        // slot on the next session-aware call/stream (and auto-saves it after each turn).
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

    /** Fork the current session: copy its conversation + model binding. */
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

    /** Clear the current conversation; optionally also wipe this session's (legacy) temp-memory file. */
    public void clearConversation(boolean withTempMemory) {
        if (currentSessionId == null) {
            return;
        }
        agentHolder.get().clearConversation(currentSessionId);
        // The prior compression snapshot for this session is now stale — reset it so a later
        // maybeCompress()/status() does not reason over the pre-clear message count.
        if (snapshotResetHook != null) {
            snapshotResetHook.accept(currentSessionId);
        }
        if (withTempMemory) {
            try {
                Files.deleteIfExists(tempMemoryPath(currentSessionId));
            } catch (IOException ignored) {
            }
        }
        touch(currentSessionId);
    }

    /**
     * Delete the given sessions; if the current one is removed, activate a replacement. Removes BOTH
     * the metadata sidecar ({@code repository.deleteById}) AND the native conversation state slot
     * ({@code (userId="pig", id)} via {@link io.pigagent.core.agent.PigAgent#deleteConversation}) so no
     * orphaned conversation is left on disk (a privacy/disk leak). Each conversation-slot delete is
     * fault-tolerant (it logs and continues), so one failure never aborts the batch.
     */
    public void delete(List<String> ids) {
        boolean currentDeleted = currentSessionId != null && ids.contains(currentSessionId);
        for (String id : ids) {
            repository.deleteById(id);
            agentHolder.get().deleteConversation(id);
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

    /**
     * Persist the current conversation and bump its last-active time. The native {@code AgentStateStore}
     * already auto-persists the main agent's {@code (pig, sessionId)} slot at the end of every turn
     * (chained before the stream completes; atomic temp+move), so this explicit {@code saveTo} is a
     * belt-and-suspenders <em>immediate flush</em> — redundant but not corrupting — and it is what
     * updates the metadata sidecar's last-active timestamp on save.
     */
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

    /**
     * Toggle native long-term memory and persist the choice ({@code /memory on|off}). Maps to the
     * top-level {@code memory-enabled} config flag, then triggers an agent rebuild (via
     * {@code memoryToggleHook}) so the native memory hooks/tools + MEMORY.md system-prompt injection
     * are turned on/off — off means no injection and no flush.
     */
    public void setMemoryEnabled(boolean enabled) {
        configManager.updateConfig(c -> c.setMemoryEnabled(enabled));
        if (memoryToggleHook != null) {
            memoryToggleHook.run();
        }
    }

    public boolean isMemoryEnabled() {
        return configManager.getConfig().isMemoryEnabled();
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
}
