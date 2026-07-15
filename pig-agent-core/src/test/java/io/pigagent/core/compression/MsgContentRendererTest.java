package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared content serializer. This is the DRY core reused by both token
 * estimation and summarization, so the fix that makes both "tool-aware" lives here: rendering a
 * message MUST include tool-call input and tool-result payloads, not just its plain text.
 */
class MsgContentRendererTest {

    private static Msg text(MsgRole role, String t) {
        return Msg.builder().name("m").role(role).content(TextBlock.builder().text(t).build()).build();
    }

    @Test
    void render_includesToolResultPayload() {
        // Arrange: an assistant message whose only content is a large tool result (no TextBlock).
        String payload = "TOOLRESULTMARKER: the file contained 42 rows";
        Msg toolMsg = Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text(payload).withIdAndName("call-1", "shell"))
                .build();

        // Act
        String rendered = MsgContentRenderer.render(toolMsg, 0);

        // Assert: the tool-result payload is present (getTextContent would have dropped it).
        assertThat(rendered).contains("TOOLRESULTMARKER").contains("42 rows").contains("shell");
    }

    @Test
    void render_includesToolCallInput() {
        // Arrange: a tool-call block carrying its arguments.
        Msg useMsg = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("c1").name("writeFile")
                        .input(Map.of("path", "/tmp/out.txt")).build())
                .build();

        // Act
        String rendered = MsgContentRenderer.render(useMsg, 0);

        // Assert
        assertThat(rendered).contains("writeFile").contains("/tmp/out.txt");
    }

    @Test
    void render_textOnly_returnsPlainText() {
        // Arrange / Act
        String rendered = MsgContentRenderer.render(text(MsgRole.USER, "hello world"), 0);

        // Assert
        assertThat(rendered).isEqualTo("hello world");
    }

    @Test
    void render_truncatesPerBlock_whenCapGiven() {
        // Arrange: a block far longer than the cap.
        String big = "x".repeat(5000);
        Msg toolMsg = Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text(big).withIdAndName("id", "shell")).build();

        // Act
        String capped = MsgContentRenderer.render(toolMsg, 100);
        String uncapped = MsgContentRenderer.render(toolMsg, 0);

        // Assert: the capped rendering is bounded (block truncated), the uncapped one keeps it all.
        assertThat(capped.length()).isLessThan(200);
        assertThat(uncapped.length()).isGreaterThan(5000);
    }

    @Test
    void render_nullMessage_isEmpty() {
        assertThat(MsgContentRenderer.render(null, 0)).isEmpty();
    }

    @Test
    void renderConversation_includesRolesAndToolContent() {
        // Arrange
        Msg user = text(MsgRole.USER, "run the build");
        Msg tool = Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text("BUILD SUCCESS in 3s").withIdAndName("c", "shell")).build();

        // Act
        String convo = MsgContentRenderer.renderConversation(List.of(user, tool), 4000);

        // Assert: both turns rendered, tool result preserved for the summarizer to keep.
        assertThat(convo).contains("run the build").contains("BUILD SUCCESS in 3s");
    }

    @Test
    void renderConversation_emptyOrNull_isEmpty() {
        assertThat(MsgContentRenderer.renderConversation(null, 0)).isEmpty();
        assertThat(MsgContentRenderer.renderConversation(List.of(), 0)).isEmpty();
    }
}
