package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.Locale;

/**
 * Pure heuristic {@link ImportanceScorer}: keyword-driven, no model, fully unit-testable.
 *
 * <p>Signals (additive, capped): decision/constraint/correction wording (EN + CJK) marks a turn as
 * high value (especially from the user); error/failure wording marks tool results and reports as
 * high value; a short bare acknowledgement ("ok", "thanks", "好的") scores the lowest. Ordinary
 * chatter and filler stay at the low base score, so they are the first to be summarized and are NOT
 * pulled out as verbatim — this is what keeps default behavior identical for plain conversations.
 */
public final class HeuristicImportanceScorer implements ImportanceScorer {

    private static final int BASE = 1;
    private static final int DECISION_WEIGHT = 4;
    private static final int ERROR_WEIGHT = 4;
    private static final int USER_DECISION_BONUS = 1;
    private static final int SHORT_ACK_MAX = 16;

    /** Decision / constraint / correction wording (matched case-insensitively as substrings). */
    private static final String[] DECISION_KEYWORDS = {
            "decide", "decision", "must not", "must ", "should not", "instead", "actually",
            "correction", "correct me", "let's use", "do not", "don't", "never ",
            "必须", "务必", "不要", "别用", "改成", "改为", "其实", "纠正", "应该", "决定", "不能"
    };

    /** Error / failure wording (matched case-insensitively as substrings). */
    private static final String[] ERROR_KEYWORDS = {
            "error", "exception", "failed", "failure", "panic", "traceback", "stack trace",
            "报错", "失败", "异常", "错误", "崩溃"
    };

    /** Short bare acknowledgements → lowest score. */
    private static final String[] ACK_PREFIXES = {
            "ok", "okay", "thanks", "thank you", "yes", "no", "sure", "got it", "sounds good",
            "嗯", "好的", "好", "收到", "谢谢", "行"
    };

    @Override
    public int score(Msg msg) {
        if (msg == null) {
            return 0;
        }
        String text = MsgContentRenderer.render(msg, 0);
        if (text == null || text.isBlank()) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String stripped = lower.strip();
        if (stripped.length() <= SHORT_ACK_MAX && startsWithAck(stripped)) {
            return 0;
        }
        int score = BASE;
        boolean decision = containsAny(lower, DECISION_KEYWORDS);
        if (decision) {
            score += DECISION_WEIGHT;
        }
        if (containsAny(lower, ERROR_KEYWORDS)) {
            score += ERROR_WEIGHT;
        }
        if (decision && msg.getRole() == MsgRole.USER) {
            score += USER_DECISION_BONUS;
        }
        return score;
    }

    private static boolean startsWithAck(String stripped) {
        for (String ack : ACK_PREFIXES) {
            if (stripped.startsWith(ack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
