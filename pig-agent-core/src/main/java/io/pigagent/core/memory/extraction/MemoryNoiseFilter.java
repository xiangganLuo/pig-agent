package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure Strategy/predicate that strips <b>noise</b> from a turn before extraction, so transient junk
 * never becomes a durable memory. Two kinds of noise are removed:
 *
 * <ul>
 *   <li><b>Tool-call machinery</b> — {@code TOOL}-role messages and any message carrying a
 *       {@link ToolUseBlock}/{@link ToolResultBlock}. The "what did the shell/file return" content
 *       is execution detail, not a durable fact.</li>
 *   <li><b>Session-ephemeral events</b> — transient status chatter like "I ran mvn test",
 *       "running the build", "let me check…". These describe a fleeting action, not knowledge worth
 *       remembering, and are matched by conservative sentence-shape heuristics on the text.</li>
 * </ul>
 *
 * <p>Stateless and side-effect-free, so it is unit-tested independently of the extractor. This is a
 * best-effort heuristic filter, not a classifier: it errs toward keeping substantive user/assistant
 * text and dropping only clearly-transient lines.
 */
public final class MemoryNoiseFilter {

    /** Transient status / "I just did X" chatter that must not become durable memory. */
    private static final Pattern EPHEMERAL = Pattern.compile(
            "^(i('m| am| have| will| just|'ll)?\\s+(ran|run|running| re-?ran|execute|executed|executing|"
                    + "start(ed|ing)?|launch(ed|ing)?|check(ed|ing)?|look(ed|ing)?|try(ing)?|tried|"
                    + "wait(ed|ing)?|now\\s+run)"
                    + "|let me\\s+|let's\\s+|running\\s+|executing\\s+|one moment|hold on|please wait|"
                    + "here('s| is)\\s+the\\s+(output|result|log)s?\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Command/build status noise (mvn/npm/gradle/git progress, test tallies, build results). */
    private static final Pattern STATUS_NOISE = Pattern.compile(
            "\\b(mvn|maven|gradle|npm|yarn|pnpm|pytest|cargo|make)\\b.*\\b(test|build|install|run|compile)\\b"
                    + "|\\bbuild (succeed|success|succeeded|failed|passing|passed|failing)\\b"
                    + "|\\btests?\\s+(passed|failed|green|red|ran)\\b"
                    + "|\\b\\d+\\s+tests?\\s+(passed|failed|run)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * True when the message carries durable, extractable content — i.e. it is NOT a tool message and
     * NOT a pure transient status line. A {@code SYSTEM} message is not durable user knowledge.
     */
    public boolean isDurable(Msg msg) {
        if (msg == null) {
            return false;
        }
        MsgRole role = msg.getRole();
        if (role == MsgRole.TOOL || role == MsgRole.SYSTEM) {
            return false;
        }
        if (hasToolBlock(msg)) {
            return false;
        }
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return false;
        }
        return !isEphemeral(text);
    }

    /** True when the text is transient status chatter (an ephemeral event, not knowledge). */
    public boolean isEphemeral(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.strip();
        // Only the first line drives the shape check (a follow-up answer may span lines).
        String firstLine = t.lines().findFirst().orElse(t).strip();
        if (EPHEMERAL.matcher(firstLine).find()) {
            return true;
        }
        // Short, single-line status messages that are dominated by build/test noise.
        return t.lines().count() <= 1 && t.length() <= 120 && STATUS_NOISE.matcher(t).find();
    }

    /** Return only the durable messages of the turn (tool + ephemeral noise removed). */
    public List<Msg> filter(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream().filter(this::isDurable).toList();
    }

    private static boolean hasToolBlock(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return false;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock || block instanceof ToolResultBlock) {
                return true;
            }
        }
        return false;
    }
}
