package io.pigagent.core.compression;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;

/**
 * Single-point serialization of a {@link Msg}'s <em>full</em> content — text plus tool-call input
 * and tool-result payloads — into a plain string.
 *
 * <p>This is the DRY core shared by both token estimation ({@link CharBudgetTokenEstimator}) and
 * conversation summarization ({@code CompressionService.ModelSummarizer}): both need to look at the
 * same complete content of a message, differing only in whether they truncate per block. Keeping
 * the "how do we render a Msg's whole content" logic in one place avoids the ad-hoc procedural
 * duplication that previously counted/serialized only {@link Msg#getTextContent()} and silently
 * dropped tool blocks (a message-heavy coding agent's tool results are exactly the KBs that matter).
 *
 * <p>Handled block types: {@link TextBlock} (its text), {@link ToolUseBlock} (name + input args),
 * {@link ToolResultBlock} (name + recursively-rendered output blocks). Other block types
 * (thinking/image/…) are intentionally not counted, keeping the renderer focused on tool-awareness
 * and avoiding unstable {@code toString()} output.
 */
public final class MsgContentRenderer {

    private static final String ELLIPSIS = "…";

    private MsgContentRenderer() {
    }

    /**
     * Render one message's full content to a string.
     *
     * @param msg             the message (null → empty string)
     * @param maxCharsPerBlock per-block truncation limit; {@code <= 0} means no truncation
     *                         (use unlimited for size estimation, a cap for summary input)
     */
    public static String render(Msg msg, int maxCharsPerBlock) {
        if (msg == null) {
            return "";
        }
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return truncate(nullToEmpty(msg.getTextContent()), maxCharsPerBlock);
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            String rendered = truncate(renderBlock(block), maxCharsPerBlock);
            if (rendered.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(rendered);
        }
        return sb.toString();
    }

    /**
     * Render a conversation as {@code role: content} lines, for a summarizer's model input.
     * Messages that render to blank are skipped.
     */
    public static String renderConversation(List<Msg> messages, int maxCharsPerBlock) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            String content = render(m, maxCharsPerBlock);
            if (content.isBlank()) {
                continue;
            }
            sb.append(m.getRole()).append(": ").append(content).append('\n');
        }
        return sb.toString();
    }

    private static String renderBlock(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return nullToEmpty(text.getText());
        }
        if (block instanceof ToolUseBlock use) {
            return "[tool_call " + nullToEmpty(use.getName()) + " " + String.valueOf(use.getInput()) + "]";
        }
        if (block instanceof ToolResultBlock result) {
            StringBuilder sb = new StringBuilder("[tool_result ").append(nullToEmpty(result.getName())).append(' ');
            List<ContentBlock> output = result.getOutput();
            if (output != null) {
                for (ContentBlock out : output) {
                    sb.append(renderBlock(out));
                }
            }
            return sb.append(']').toString();
        }
        // Other block types (thinking/image/audio/…) are not counted for tool-awareness.
        return "";
    }

    private static String truncate(String s, int maxCharsPerBlock) {
        if (s == null) {
            return "";
        }
        if (maxCharsPerBlock <= 0 || s.length() <= maxCharsPerBlock) {
            return s;
        }
        return s.substring(0, maxCharsPerBlock) + ELLIPSIS;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
