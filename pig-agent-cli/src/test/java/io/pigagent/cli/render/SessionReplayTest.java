package io.pigagent.cli.render;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure {@link SessionReplay}: it renders the bounded tail of a restored conversation for the
 * CC-style "resumed session" replay (F2). Covers the last-K bound, user/assistant/tool rendering,
 * empty → nothing, credential redaction, and surrogate-safe truncation.
 */
class SessionReplayTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg assistant(String text) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg assistantToolCall(String toolName) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(List.of(ToolUseBlock.builder()
                        .id("c1").name(toolName).input(Map.of()).content("{}").build()))
                .build();
    }

    private static Msg toolResult(String toolName, String output) {
        return Msg.builder().name("tool").role(MsgRole.TOOL)
                .content(ToolResultBlock.text(output).withIdAndName("c1", toolName))
                .build();
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void emptyConversationRendersNothing() {
        assertThat(SessionReplay.render(List.of(), 8)).isEmpty();
        assertThat(SessionReplay.render(null, 8)).isEmpty();
        assertThat(SessionReplay.resumeLines("s", List.of(), 8)).isEmpty();
    }

    @Test
    void rendersUserEchoAssistantAndToolBlocks() {
        List<Msg> msgs = List.of(
                user("what files are here"),
                assistantToolCall("listDirectory"),
                toolResult("listDirectory", "3 files: a.txt b.txt c.txt"),
                assistant("There are **3** files."));

        String out = joined(SessionReplay.render(msgs, 8));

        assertThat(out).contains("› what files are here");     // user echo prefix
        assertThat(out).contains("⏺").contains("listDirectory"); // tool head
        assertThat(out).contains("└").contains("3 files");       // tool result body
        assertThat(out).contains("3 files").contains("files.");  // assistant answer text present
    }

    @Test
    void boundsToLastKMessages() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            msgs.add(user("message-number-" + i));
        }

        String out = joined(SessionReplay.render(msgs, 8));

        // Only the last 8 (messages 4..11) are replayed.
        assertThat(out).doesNotContain("message-number-0").doesNotContain("message-number-3");
        assertThat(out).contains("message-number-4").contains("message-number-11");
    }

    @Test
    void resumeLinesPrependsDimHeaderWithCount() {
        List<Msg> msgs = List.of(user("hi"), assistant("hello"));

        List<String> lines = SessionReplay.resumeLines("my-session", msgs, 8);

        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).contains("已恢复会话").contains("my-session").contains("最近 2 条");
    }

    @Test
    void resumeLinesCountIsCappedAtK() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            msgs.add(user("m" + i));
        }

        List<String> lines = SessionReplay.resumeLines("s", msgs, 8);

        assertThat(lines.get(0)).contains("最近 8 条");
    }

    @Test
    void redactsCredentialsInAnyRole() {
        List<Msg> msgs = List.of(
                user("my key is sk-ABCDEF0123456789XYZ"),
                assistant("stored token=SUPERSECRETVALUE for you"),
                toolResult("readFile", "Authorization: Bearer abcdef0123456789"));

        String out = joined(SessionReplay.render(msgs, 8));

        assertThat(out).doesNotContain("SUPERSECRETVALUE");
        assertThat(out).doesNotContain("sk-ABCDEF0123456789XYZ");
        assertThat(out).doesNotContain("abcdef0123456789");
        assertThat(out).contains("token=***");
    }

    @Test
    void truncatesLongTextOnSurrogateBoundarySafely() {
        // Emoji straddles the cap boundary → truncation must not emit a lone high surrogate.
        String text = "x".repeat(SessionReplay.MAX_TEXT_CHARS - 1) + "😀";
        List<Msg> msgs = List.of(assistant(text));

        String out = joined(SessionReplay.render(msgs, 8));

        assertThat(out).contains("…");
        assertThat(out).doesNotContain("\uD83D"); // no dangling high surrogate
        assertNoLoneSurrogate(out);
    }

    private static void assertNoLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                        .as("high surrogate at %d must be paired", i).isTrue();
            } else {
                assertThat(Character.isLowSurrogate(c))
                        .as("no lone low surrogate at %d", i).isFalse();
            }
        }
    }
}
