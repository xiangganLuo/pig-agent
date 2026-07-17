package io.pigagent.core.compression;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Automatic in-memory context compression, upgraded to structured <em>context engineering</em>.
 *
 * <p>Operates ONLY on the agent's in-memory conversation for a given session slot
 * ({@code agent.getMemory(sessionId)}, the {@code (userId="pig", sessionId)} conversation the model
 * actually sees); the persisted session history and the two-tier memory are never summarized here.
 * The rewrite IS persisted back to that same slot right after it lands (see {@code compress}) — the
 * native store reloads the slot at the start of every turn, so an unsaved in-memory rewrite would be
 * silently discarded on the next turn. When the estimated token usage crosses the threshold, the
 * conversation is rewritten by a
 * {@link ContextEngineer}: a three-tier {@link ContextBudget} (pinned / recent verbatim /
 * summarized), importance-based verbatim retention ({@link ImportanceScorer}), verbatim protection
 * of code/commands/IDs ({@link VerbatimGuard}), recursive summarization ({@link RecursiveSummarizer})
 * and a post-compression {@link ConsistencyChecker} with a safe (less-aggressive) fallback. The
 * summarizer model call stays behind the {@link Summarizer} seam, so the whole pipeline is
 * testable offline. Compression is fully skipped on any failure, leaving the original context intact.
 *
 * <p>With {@link EngineeringOptions#defaults()} the pipeline reproduces the prior
 * "summarize-old, keep-recent" behavior on small conversations, so it is a backward-compatible,
 * additive upgrade. After a successful compression an optional {@link CompressionLineageRecorder}
 * records session provenance — independent of the compression core, its failures are swallowed.
 */
public final class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);

    /** Minimum new messages since the last compression before compressing again (anti-thrash). */
    private static final int MIN_GROWTH = 4;
    /** Per-block truncation cap for summarizer input, so one giant tool result can't blow it up. */
    private static final int MAX_SUMMARY_CHARS_PER_BLOCK = 4000;

    /** Strategy for token estimation (shares {@link MsgContentRenderer} with the summarizer). */
    private static final TokenEstimator TOKEN_ESTIMATOR = new CharBudgetTokenEstimator();

    /** Summarizes a batch of older messages into a single string (null/blank aborts compression). */
    @FunctionalInterface
    public interface Summarizer {
        String summarize(List<Msg> older);
    }

    /** Resolves the live {@link Memory} view for a session slot (session-scoped; may return null). */
    private final Function<String, Memory> memoryFn;
    /** Persists a session slot after its conversation is rewritten (no-op seam for tests). */
    private final Consumer<String> persister;
    private final ContextEngineer engineer;
    private final CompressionLineageRecorder lineageRecorder;
    private final int budgetTokens;
    private final double threshold;
    private final boolean defaultEnabled;
    private final int keepRecent;
    private final BudgetRatios ratios;

    private final Map<String, Boolean> enabledBySession = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCompressedAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastCompressedSize = new ConcurrentHashMap<>();

    public CompressionService(AgentHolder agentHolder, int budgetTokens, double threshold, boolean defaultEnabled) {
        this(agentHolder, budgetTokens, threshold, defaultEnabled, CompressionLineageRecorder.NOOP);
    }

    public CompressionService(AgentHolder agentHolder, int budgetTokens, double threshold,
                              boolean defaultEnabled, CompressionLineageRecorder lineageRecorder) {
        this(agentHolder, budgetTokens, threshold, defaultEnabled, lineageRecorder, EngineeringOptions.defaults());
    }

    public CompressionService(AgentHolder agentHolder, int budgetTokens, double threshold,
                              boolean defaultEnabled, CompressionLineageRecorder lineageRecorder,
                              EngineeringOptions options) {
        this(sessionId -> {
                    PigAgent agent = agentHolder.get();
                    return agent == null ? null : agent.getMemory(sessionId);
                },
                sessionId -> {
                    PigAgent agent = agentHolder.get();
                    if (agent != null) {
                        agent.saveTo(sessionId);
                    }
                },
                new ModelSummarizer(agentHolder),
                budgetTokens, threshold, defaultEnabled, lineageRecorder, options);
    }

    /** Injectable-seam constructor (default options) — package-private for unit tests. */
    CompressionService(Function<String, Memory> memoryFn, Consumer<String> persister, Summarizer summarizer,
                       int budgetTokens, double threshold, boolean defaultEnabled,
                       CompressionLineageRecorder lineageRecorder) {
        this(memoryFn, persister, summarizer, budgetTokens, threshold, defaultEnabled, lineageRecorder,
                EngineeringOptions.defaults());
    }

    /** Full injectable-seam constructor with engineering options — package-private for unit tests. */
    CompressionService(Function<String, Memory> memoryFn, Consumer<String> persister, Summarizer summarizer,
                       int budgetTokens, double threshold, boolean defaultEnabled,
                       CompressionLineageRecorder lineageRecorder, EngineeringOptions options) {
        this.memoryFn = memoryFn;
        this.persister = persister == null ? sessionId -> { } : persister;
        EngineeringOptions opts = options == null ? EngineeringOptions.defaults() : options;
        this.engineer = ContextEngineer.withDefaults(summarizer, opts);
        this.lineageRecorder = lineageRecorder == null ? CompressionLineageRecorder.NOOP : lineageRecorder;
        this.budgetTokens = budgetTokens;
        this.threshold = threshold;
        this.defaultEnabled = defaultEnabled;
        this.keepRecent = opts.keepRecent();
        this.ratios = opts.ratios();
    }

    public boolean isEnabled(String sessionId) {
        return enabledBySession.getOrDefault(sessionId, defaultEnabled);
    }

    public void setEnabled(String sessionId, boolean enabled) {
        if (sessionId != null) {
            enabledBySession.put(sessionId, enabled);
        }
    }

    /** Reset the compression snapshot for a session (e.g. when its conversation is cleared). */
    public void resetSnapshot(String sessionId) {
        if (sessionId != null) {
            lastCompressedAt.remove(sessionId);
            lastCompressedSize.remove(sessionId);
        }
    }

    /** Auto-trigger: compress only if enabled, over threshold, and enough new content exists. */
    public void maybeCompress(String sessionId) {
        if (sessionId == null || !isEnabled(sessionId)) {
            return;
        }
        Memory memory = currentMemory(sessionId);
        if (memory == null) {
            return;
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.size() <= keepRecent) {
            return;
        }
        if (TOKEN_ESTIMATOR.estimate(messages) < threshold * budgetTokens) {
            return;
        }
        int sinceLast = messages.size() - lastCompressedSize.getOrDefault(sessionId, 0);
        if (sinceLast < MIN_GROWTH) {
            return; // avoid frequent repeated compression
        }
        compress(sessionId, memory, messages);
    }

    /** Manual trigger (/compress now): compress regardless of threshold if there is anything to do. */
    public boolean compressNow(String sessionId) {
        Memory memory = currentMemory(sessionId);
        if (memory == null) {
            return false;
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.size() <= keepRecent) {
            return false;
        }
        return compress(sessionId, memory, messages);
    }

    public CompressionStatus status(String sessionId) {
        Memory memory = currentMemory(sessionId);
        List<Msg> messages = memory == null ? List.of() : memory.getMessages();
        if (messages == null) {
            messages = List.of();
        }
        return new CompressionStatus(
                isEnabled(sessionId),
                TOKEN_ESTIMATOR.estimate(messages),
                budgetTokens,
                (int) (threshold * budgetTokens),
                messages.size(),
                lastCompressedAt.getOrDefault(sessionId, 0L),
                ContextBudget.allocate(budgetTokens, ratios));
    }

    private boolean compress(String sessionId, Memory memory, List<Msg> messages) {
        try {
            List<Msg> plan = engineer.rewrite(messages, budgetTokens);
            if (plan == null || plan.isEmpty()) {
                return false; // nothing to do / blank summary / safe fallback → keep original
            }

            // Only mutate memory once we have a valid rewrite plan.
            memory.clear();
            for (Msg m : plan) {
                memory.addMessage(m);
            }

            if (sessionId != null) {
                lastCompressedAt.put(sessionId, System.currentTimeMillis());
                lastCompressedSize.put(sessionId, memory.getMessages().size());
                // Persist the rewritten slot immediately: the native store reloads the
                // (pig, sessionId) slot at the start of the next turn, so an unsaved rewrite would be
                // discarded and compression silently reverted. This is the load-bearing HIGH fix.
                persist(sessionId);
                recordLineage(sessionId);
            }
            return true;
        } catch (Exception e) {
            // Abort compression entirely; original context is unchanged (§八).
            log.warn("Compression skipped: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Record the session's compression lineage. Independent of the compression core: memory has
     * already been rewritten; any failure here is logged and swallowed so lineage tracking can never
     * break a successful compression (design D4 / R3).
     */
    private void recordLineage(String sessionId) {
        try {
            lineageRecorder.recordCompression(sessionId);
        } catch (Exception e) {
            log.warn("Compression lineage record skipped for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Persist the just-rewritten session slot so the native per-turn reload observes the compressed
     * conversation. Fault-tolerant: a persist failure is logged and swallowed (the in-memory rewrite
     * still stands for the current process) so it can never abort a successful compression.
     */
    private void persist(String sessionId) {
        try {
            persister.accept(sessionId);
        } catch (Exception e) {
            log.warn("Compression persist skipped for session {}: {}", sessionId, e.getMessage());
        }
    }

    private Memory currentMemory(String sessionId) {
        return memoryFn.apply(sessionId);
    }

    /** Default summarizer: spins up a throwaway agent on the live model to write the summary. */
    private static final class ModelSummarizer implements Summarizer {

        private static final String SUMMARY_PROMPT = """
                You compress conversation history. Produce a concise summary of the messages below.
                STRICTLY PRESERVE: the user's explicit requirements, constraints, goals; final
                decisions, conclusions and solutions; important tool results and errors. DROP:
                repeated back-and-forth, retries, redundant confirmations, verbose logs. If older
                content conflicts with later content, keep the later. Output only the summary.
                """;

        private final AgentHolder agentHolder;

        ModelSummarizer(AgentHolder agentHolder) {
            this.agentHolder = agentHolder;
        }

        @Override
        public String summarize(List<Msg> older) {
            Model model = agentHolder.get().getModel();
            PigAgent summarizer = PigAgent.builder()
                    .name("compressor")
                    .sysPrompt(SUMMARY_PROMPT)
                    .model(model)
                    .build();
            // Render the full content of each older message — text AND tool-call input + tool
            // results (compactly, truncated per block) — so the summary can actually preserve the
            // "what did that command/file return" context the prompt asks it to keep.
            String conversation = MsgContentRenderer.renderConversation(older, MAX_SUMMARY_CHARS_PER_BLOCK);
            Msg reply = summarizer.call(Msg.builder().name("user").role(MsgRole.USER)
                    .content(io.agentscope.core.message.TextBlock.builder().text(conversation).build()).build());
            return reply == null ? null : reply.getTextContent();
        }
    }
}
