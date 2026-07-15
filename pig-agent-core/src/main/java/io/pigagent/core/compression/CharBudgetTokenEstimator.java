package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;

import java.util.List;

/**
 * Default {@link TokenEstimator}: a coarse {@code ~chars / 4} heuristic applied to each message's
 * <em>full</em> serialized content ({@link MsgContentRenderer#render} with no truncation), so text,
 * tool-call input, and tool-result payloads are all counted.
 *
 * <p>This deliberately does NOT undertake precise tokenization — it only fixes the systematic
 * under-count from counting text alone, which let this tool-heavy agent's context blow past the
 * budget before compression ever triggered. The estimate stays approximate for CJK / code / JSON.
 */
public final class CharBudgetTokenEstimator implements TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    @Override
    public int estimate(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long chars = 0;
        for (Msg m : messages) {
            chars += MsgContentRenderer.render(m, 0).length(); // 0 → unlimited: reflect true size
        }
        return (int) (chars / CHARS_PER_TOKEN);
    }
}
