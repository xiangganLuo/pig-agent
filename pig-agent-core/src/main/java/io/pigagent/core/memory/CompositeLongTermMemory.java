package io.pigagent.core.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Two-tier long-term memory that the agent sees as a single {@link LongTermMemory}.
 *
 * <ul>
 *   <li><b>Global memory</b> — shared across all sessions, curated (personal preferences,
 *       general rules). Read on every retrieval; never auto-grown here.</li>
 *   <li><b>Session temporary memory</b> — scoped to the current session. Swapped via
 *       {@link #setSessionMemory(LongTermMemory)} when the active session changes, so the
 *       agent never has to be rebuilt. Auto-extracted conversation insights are recorded
 *       here, keeping sessions isolated.</li>
 * </ul>
 *
 * <p>A global enable switch ({@link #setEnabled(boolean)}) lets the user temporarily turn
 * off all memory reading; while disabled, {@link #retrieve(Msg)} returns nothing and
 * {@link #record(List)} is a no-op.
 *
 * <p>Retrieved content is prefixed with a source label so the agent can distinguish
 * where a memory came from.
 */
public final class CompositeLongTermMemory implements LongTermMemory {

    private static final String GLOBAL_LABEL = "[Global memory]";
    private static final String SESSION_LABEL = "[Session memory]";

    private final LongTermMemory globalMemory;
    private volatile LongTermMemory sessionMemory;
    private volatile boolean enabled;

    public CompositeLongTermMemory(LongTermMemory globalMemory, boolean enabled) {
        this.globalMemory = globalMemory;
        this.enabled = enabled;
    }

    /** Repoint the per-session temporary memory (called on session switch). */
    public void setSessionMemory(LongTermMemory sessionMemory) {
        this.sessionMemory = sessionMemory;
    }

    /** Toggle all memory reading/writing on or off. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        if (!enabled || sessionMemory == null) {
            return Mono.empty();
        }
        // Conversation insights stay scoped to the current session.
        return sessionMemory.record(messages);
    }

    @Override
    public Mono<String> retrieve(Msg query) {
        if (!enabled) {
            return Mono.just("");
        }
        Mono<String> global = globalMemory != null ? globalMemory.retrieve(query) : Mono.just("");
        Mono<String> session = sessionMemory != null ? sessionMemory.retrieve(query) : Mono.just("");
        return Mono.zip(global, session, (g, s) -> {
            StringBuilder sb = new StringBuilder();
            append(sb, GLOBAL_LABEL, g);
            append(sb, SESSION_LABEL, s);
            return sb.toString();
        });
    }

    private static void append(StringBuilder sb, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(label).append('\n').append(content.strip());
    }
}
