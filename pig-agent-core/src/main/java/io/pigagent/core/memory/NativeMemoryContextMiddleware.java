package io.pigagent.core.memory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.pigagent.core.memory.injection.MemoryInjectionSettings;
import io.pigagent.core.memory.injection.MemoryRetriever;
import io.pigagent.core.memory.injection.PinnedSelector;
import io.pigagent.core.memory.injection.RetrievedFactsFormatter;
import io.pigagent.core.memory.search.MemoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Injects the AgentScope 2.0 native consolidated long-term memory ({@code MEMORY.md}) into the model
 * context (capability {@code pa-memory-native}, OD2-A). This is the injection half of the native
 * two-layer memory: the native {@code MemoryFlushMiddleware} appends extracted facts to the daily
 * ledger ({@code memory/YYYY-MM-DD.md}) and {@code MemoryMaintenanceMiddleware} consolidates them into
 * the workspace-level {@code MEMORY.md}; this middleware surfaces that curated file to the model.
 *
 * <p><b>Two injection modes.</b>
 * <ul>
 *   <li><b>Whole-file (default, {@code memory.injection} disabled or absent).</b> {@code onSystemPrompt}
 *       appends the entire {@code MEMORY.md} under {@link #MEMORY_HEADER}; {@code onReasoning} is
 *       identity. This is the original {@code pa-memory-native} behavior — byte-for-byte unchanged.</li>
 *   <li><b>Retrieval injection ({@code memory.injection} enabled, capability
 *       {@code memory-retrieval-injection}).</b> Injection is split to preserve prefix-cache stability
 *       (see below): {@code onSystemPrompt} injects only a stable, query-independent <b>pinned core</b>
 *       (under {@link #PINNED_HEADER}); {@code onReasoning} retrieves the top-K facts relevant to the
 *       current user message and injects them as a <b>trailing ephemeral</b> user-side {@link Msg}
 *       (never persisted, rebuilt every reasoning step).</li>
 * </ul>
 *
 * <p><b>Prefix-cache invariant (the load-bearing decision).</b> {@code onSystemPrompt}'s signature has
 * no access to the current query, so the pinned injection is structurally query-independent and stays
 * byte-stable across turns (it forms the cached system-prompt prefix). Query-dependent facts live only
 * in the {@code onReasoning} trailing message — <em>after</em> the system prompt, not part of the cached
 * prefix — so they change per turn without invalidating the prefix cache. This mirrors the ephemeral
 * trailing-{@code Msg} mechanism used by {@code LoopDetectionMiddleware} (the retired
 * {@code EphemeralMemoryMiddleware} injection path).
 *
 * <p><b>Fault-tolerant.</b> A missing/blank/unreadable {@code MEMORY.md} yields the prompt unchanged
 * (no injection); an IO error is logged at debug and swallowed. Retrieval failures degrade to no
 * query-aware injection for that step — memory injection never breaks a turn. The file is read fresh
 * each call so a consolidation is picked up on the next turn.
 */
public final class NativeMemoryContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(NativeMemoryContextMiddleware.class);

    /** Header prefixing the whole-file memory block in the system prompt (default mode). */
    static final String MEMORY_HEADER = "## Long-term Memory (MEMORY.md)";

    /** Header prefixing the pinned-core block in the system prompt (retrieval-injection mode). */
    static final String PINNED_HEADER = "## Pinned Memory (MEMORY.md)";

    /** Name of the injected trailing ephemeral message carrying query-aware facts. */
    static final String RETRIEVAL_MSG_NAME = "memory_retrieval";

    private final Path memoryFile;
    private final MemoryInjectionSettings settings; // null → whole-file mode (today's behavior)
    private final MemoryRetriever retriever;        // null → no query-aware injection

    /** @param memoryFile the workspace-level {@code MEMORY.md} (native consolidated memory). */
    public NativeMemoryContextMiddleware(Path memoryFile) {
        this(memoryFile, null, null);
    }

    /**
     * @param memoryFile the workspace-level {@code MEMORY.md}
     * @param settings   RAG-style injection settings; {@code null} (or {@code !enabled()}) keeps the
     *                   whole-file behavior
     * @param retriever  the query-aware retrieval seam ({@code MemorySearchIndex::search}); {@code null}
     *                   disables query-aware injection (pinned-only)
     */
    public NativeMemoryContextMiddleware(Path memoryFile, MemoryInjectionSettings settings,
                                         MemoryRetriever retriever) {
        this.memoryFile = Objects.requireNonNull(memoryFile, "memoryFile");
        this.settings = settings;
        this.retriever = retriever;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String memory = readMemory();
        if (memory.isBlank()) {
            return Mono.justOrEmpty(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        if (!injectionEnabled()) {
            // Default: whole MEMORY.md under MEMORY_HEADER (byte-identical to pre-retrieval-injection).
            return Mono.just(appendBlock(base, MEMORY_HEADER, memory));
        }
        // Retrieval-injection mode: only the stable, query-independent pinned core enters the (cached)
        // system prompt. If there is no pinned content, the prompt is left untouched — retrieval covers
        // everything via onReasoning.
        String pinned = PinnedSelector.select(memory, settings);
        if (pinned.isBlank()) {
            return Mono.justOrEmpty(currentPrompt);
        }
        return Mono.just(appendBlock(base, PINNED_HEADER, pinned));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return next.apply(maybeInjectRetrieved(input));
    }

    /** Append a retrieved-facts trailing ephemeral message when injection is enabled and produces hits. */
    private ReasoningInput maybeInjectRetrieved(ReasoningInput input) {
        if (input == null || !injectionEnabled() || retriever == null) {
            return input;
        }
        List<Msg> messages = input.messages();
        String query = lastUserText(messages);
        if (query == null || query.isBlank()) {
            return input;
        }
        List<MemoryDocument> hits;
        try {
            hits = retriever.retrieve(query, settings.topK());
        } catch (RuntimeException e) {
            log.debug("Memory retrieval failed — no query-aware injection this step: {}", e.getMessage());
            return input;
        }
        if (hits == null || hits.isEmpty()) {
            return input;
        }
        String pinned = PinnedSelector.select(readMemory(), settings);
        List<MemoryDocument> deduped = RetrievedFactsFormatter.dedupAgainstPinned(hits, pinned);
        String facts = RetrievedFactsFormatter.format(deduped);
        if (facts.isBlank()) {
            return input;
        }
        // Ephemeral, non-prefix injection: a NEW ReasoningInput with a trailing user-side Msg. The
        // incoming list is never mutated and this is rebuilt every reasoning step (never persisted).
        List<Msg> augmented = new ArrayList<>(messages);
        augmented.add(retrievedMsg(facts));
        return new ReasoningInput(augmented, input.tools(), input.options());
    }

    private boolean injectionEnabled() {
        return settings != null && settings.enabled();
    }

    private static String appendBlock(String base, String header, String body) {
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n\n";
        return base + separator + header + "\n" + body.strip() + "\n";
    }

    private static Msg retrievedMsg(String facts) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(RETRIEVAL_MSG_NAME)
                .content(TextBlock.builder().text(facts).build())
                .build();
    }

    /** The text of the last USER message (the current query), or {@code null} when there is none. */
    private static String lastUserText(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m != null && m.getRole() == MsgRole.USER) {
                return m.getTextContent();
            }
        }
        return null;
    }

    /** Read {@code MEMORY.md} (empty when absent/unreadable — never throws). */
    private String readMemory() {
        try {
            if (!Files.isRegularFile(memoryFile)) {
                return "";
            }
            return Files.readString(memoryFile, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read MEMORY.md at {}: {}", memoryFile, e.getMessage());
            return "";
        }
    }
}
