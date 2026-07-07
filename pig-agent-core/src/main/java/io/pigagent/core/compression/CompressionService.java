package io.pigagent.core.compression;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic in-memory context compression.
 *
 * <p>Operates ONLY on the agent's in-memory conversation ({@code agent.getMemory()}); the
 * persisted session history and the two-tier memory are never read or modified here. When the
 * estimated token usage crosses the threshold, the oldest messages are replaced with a single
 * model-written summary while the most recent rounds are kept verbatim. Compression follows the
 * current model (it summarizes with whatever model the live agent uses) and is fully skipped on
 * any failure, leaving the original context intact.
 */
public final class CompressionService {

    private static final Logger log = LoggerFactory.getLogger(CompressionService.class);

    /** Number of trailing messages kept verbatim (~3 user/assistant rounds). */
    private static final int KEEP_RECENT = 6;
    /** Minimum new messages since the last compression before compressing again (anti-thrash). */
    private static final int MIN_GROWTH = 4;
    private static final int CHARS_PER_TOKEN = 4;

    private static final String SUMMARY_PROMPT = """
            You compress conversation history. Produce a concise summary of the messages below.
            STRICTLY PRESERVE: the user's explicit requirements, constraints, goals; final
            decisions, conclusions and solutions; important tool results and errors. DROP:
            repeated back-and-forth, retries, redundant confirmations, verbose logs. If older
            content conflicts with later content, keep the later. Output only the summary.
            """;

    private final AgentHolder agentHolder;
    private final int budgetTokens;
    private final double threshold;
    private final boolean defaultEnabled;

    private final Map<String, Boolean> enabledBySession = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCompressedAt = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastCompressedSize = new ConcurrentHashMap<>();

    public CompressionService(AgentHolder agentHolder, int budgetTokens, double threshold, boolean defaultEnabled) {
        this.agentHolder = agentHolder;
        this.budgetTokens = budgetTokens;
        this.threshold = threshold;
        this.defaultEnabled = defaultEnabled;
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
        Memory memory = currentMemory();
        if (memory == null) {
            return;
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.size() <= KEEP_RECENT) {
            return;
        }
        if (estimateTokens(messages) < threshold * budgetTokens) {
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
        Memory memory = currentMemory();
        if (memory == null) {
            return false;
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.size() <= KEEP_RECENT) {
            return false;
        }
        return compress(sessionId, memory, messages);
    }

    public CompressionStatus status(String sessionId) {
        Memory memory = currentMemory();
        List<Msg> messages = memory == null ? List.of() : memory.getMessages();
        if (messages == null) {
            messages = List.of();
        }
        return new CompressionStatus(
                isEnabled(sessionId),
                estimateTokens(messages),
                budgetTokens,
                (int) (threshold * budgetTokens),
                messages.size(),
                lastCompressedAt.getOrDefault(sessionId, 0L));
    }

    private boolean compress(String sessionId, Memory memory, List<Msg> messages) {
        try {
            int splitAt = messages.size() - KEEP_RECENT;
            // Copy out of the live list: clear() below may invalidate sub-list views.
            List<Msg> older = new java.util.ArrayList<>(messages.subList(0, splitAt));
            List<Msg> kept = new java.util.ArrayList<>(messages.subList(splitAt, messages.size()));

            String summary = summarize(older);
            if (summary == null || summary.isBlank()) {
                return false; // keep original context on empty summary
            }

            // Only mutate memory once we successfully have a summary.
            memory.clear();
            memory.addMessage(Msg.builder().name("summary").role(MsgRole.ASSISTANT)
                    .content(TextBlock.builder()
                            .text("[Earlier conversation summary]\n" + summary).build())
                    .build());
            for (Msg m : kept) {
                memory.addMessage(m);
            }

            if (sessionId != null) {
                lastCompressedAt.put(sessionId, System.currentTimeMillis());
                lastCompressedSize.put(sessionId, memory.getMessages().size());
            }
            return true;
        } catch (Exception e) {
            // Abort compression entirely; original context is unchanged (§八).
            log.warn("Compression skipped: {}", e.getMessage());
            return false;
        }
    }

    private String summarize(List<Msg> older) {
        Model model = agentHolder.get().getModel();
        PigAgent summarizer = PigAgent.builder()
                .name("compressor")
                .sysPrompt(SUMMARY_PROMPT)
                .model(model)
                .build();
        StringBuilder sb = new StringBuilder();
        for (Msg m : older) {
            String text = m.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(m.getRole()).append(": ").append(text).append("\n");
        }
        Msg reply = summarizer.call(Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(sb.toString()).build()).build());
        return reply == null ? null : reply.getTextContent();
    }

    private Memory currentMemory() {
        PigAgent agent = agentHolder.get();
        return agent == null ? null : agent.getMemory();
    }

    private int estimateTokens(List<Msg> messages) {
        long chars = 0;
        for (Msg m : messages) {
            String text = m.getTextContent();
            if (text != null) {
                chars += text.length();
            }
        }
        return (int) (chars / CHARS_PER_TOKEN);
    }
}
