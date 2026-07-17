package io.pigagent.cli.render;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.fusesource.jansi.Ansi.Color;

import java.util.ArrayList;
import java.util.List;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Pure, terminal-free renderer for a CC-style "resumed session" replay — the bounded tail of a
 * restored conversation, shown when the user lands in a populated session (at REPL startup or after
 * {@code /session switch}). Without this the screen is blank after "Agent ready" even though the
 * native {@code AgentStateStore} reloaded the conversation, making it look like history was lost
 * (F2 is a <em>display</em> gap, not data loss).
 *
 * <p>Rendering mirrors the live turn: a USER text message echoes as a subtle dim {@code › text}; an
 * ASSISTANT text message renders through {@link MarkdownAnsiRenderer}; a tool call/result renders
 * through {@link ToolCallFormatter} ({@code ⏺ name} head + dim {@code └ summary} body). Every piece of
 * text is credential-redacted ({@link ToolCallFormatter#redact}) and length-capped on a code-point
 * boundary (never splitting a surrogate pair), so secrets never reach the terminal and a long history
 * can't flood the screen. Bounded to the last {@code maxMessages} — a few turns, not the whole log.
 *
 * <p>All methods are pure functions returning ANSI-ready lines; Jansi is used only as a string builder
 * (no {@code AnsiConsole.systemInstall()}), consistent with the rest of the render package. An empty
 * (or {@code null}) conversation renders nothing.
 */
public final class SessionReplay {

    /** Default replay depth: the last few turns' worth of messages. */
    public static final int DEFAULT_MAX_MESSAGES = 8;

    /** Per-message text cap for replay (surrogate-safe truncation). Package-private for the test. */
    static final int MAX_TEXT_CHARS = 600;

    private SessionReplay() {
    }

    /**
     * The full "resumed session" block: a dim header line naming the session + count, then the rendered
     * tail. Returns an empty list for an empty/null conversation (render nothing).
     *
     * @param sessionName  the session's display name (redacted defensively)
     * @param messages     the session's conversation, oldest → newest
     * @param maxMessages  bound on how many trailing messages to replay ({@code <= 0} → default)
     */
    public static List<String> resumeLines(String sessionName, List<Msg> messages, int maxMessages) {
        List<String> lines = render(messages, maxMessages);
        if (lines.isEmpty()) {
            return lines;
        }
        int k = maxMessages <= 0 ? DEFAULT_MAX_MESSAGES : maxMessages;
        int shown = Math.min(messages.size(), k);
        List<String> out = new ArrayList<>(lines.size() + 1);
        out.add(dim("⟳ 已恢复会话「" + safeName(sessionName) + "」· 最近 " + shown + " 条"));
        out.addAll(lines);
        return out;
    }

    /**
     * Render the last {@code maxMessages} messages to ordered ANSI lines. Empty/null input → empty list.
     * Each message contributes zero or more lines (text echo, markdown answer, tool head/body).
     */
    public static List<String> render(List<Msg> messages, int maxMessages) {
        List<String> out = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return out;
        }
        int k = maxMessages <= 0 ? DEFAULT_MAX_MESSAGES : maxMessages;
        int from = Math.max(0, messages.size() - k);
        for (int i = from; i < messages.size(); i++) {
            renderMessage(messages.get(i), out);
        }
        return out;
    }

    private static void renderMessage(Msg msg, List<String> out) {
        if (msg == null) {
            return;
        }
        boolean user = msg.getRole() == MsgRole.USER;
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            addText(msg.getTextContent(), user, out);
            return;
        }
        for (ContentBlock block : blocks) {
            renderBlock(block, user, out);
        }
    }

    private static void renderBlock(ContentBlock block, boolean user, List<String> out) {
        if (block instanceof TextBlock text) {
            addText(text.getText(), user, out);
        } else if (block instanceof ToolUseBlock use) {
            out.add(ToolCallFormatter.head(safeName(use.getName())));
        } else if (block instanceof ToolResultBlock result) {
            String body = renderOutput(result.getOutput());
            if (!body.isBlank()) {
                out.add(ToolCallFormatter.body(body));
            }
        }
        // Other block types (thinking/image/…) are intentionally not replayed.
    }

    /** Add one text block: a dim {@code › one-liner} user echo, or a markdown-rendered assistant line. */
    private static void addText(String text, boolean user, List<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        String clamped = clamp(ToolCallFormatter.redact(text.strip()), MAX_TEXT_CHARS);
        if (user) {
            String oneLine = clamped.replaceAll("\\s*\\R\\s*", " ");
            out.add(dim("› " + oneLine));
        } else {
            out.add(MarkdownAnsiRenderer.render(clamped));
        }
    }

    /** Concatenate a tool result's output text blocks into a single string (other blocks skipped). */
    private static String renderOutput(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock text && text.getText() != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /** Redacted, surrogate-safe truncation with an ellipsis when over the cap. */
    private static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return ToolCallFormatter.codePointSafeSubstring(s, max) + "…";
    }

    /** A safe display name: never null/blank, credential-redacted. */
    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "session";
        }
        return ToolCallFormatter.redact(name.strip());
    }

    /** A dim (bright-black) line — the same subtle style used across the CC-REPL render path. */
    private static String dim(String text) {
        return ansi().fgBright(Color.BLACK).a(text).reset().toString();
    }
}
