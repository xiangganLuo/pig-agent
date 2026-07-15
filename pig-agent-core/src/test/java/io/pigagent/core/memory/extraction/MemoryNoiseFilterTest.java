package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure noise filter: strips tool machinery + ephemeral status, keeps substantive user/assistant text. */
class MemoryNoiseFilterTest {

    private final MemoryNoiseFilter filter = new MemoryNoiseFilter();

    private static Msg text(MsgRole role, String t) {
        return Msg.builder().name("m").role(role).content(TextBlock.builder().text(t).build()).build();
    }

    @Test
    void stripsToolRoleAndToolBlocks() {
        Msg user = text(MsgRole.USER, "Remember I prefer Chinese in all responses.");
        Msg toolResult = Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text("BUILD SUCCESS").withIdAndName("c1", "shell")).build();
        Msg toolCall = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id("c2").name("executeCommand")
                        .input(Map.of("cmd", "mvn test")).build()).build();

        List<Msg> kept = filter.filter(List.of(user, toolResult, toolCall));

        assertThat(kept).containsExactly(user);
    }

    @Test
    void dropsSystemMessages() {
        assertThat(filter.isDurable(text(MsgRole.SYSTEM, "You are a helpful assistant."))).isFalse();
    }

    @Test
    void ephemeralStatusIsNoise() {
        assertThat(filter.isEphemeral("I ran mvn test")).isTrue();
        assertThat(filter.isEphemeral("Running the build now")).isTrue();
        assertThat(filter.isEphemeral("Let me check the file")).isTrue();
        assertThat(filter.isEphemeral("I'll run the tests")).isTrue();
        assertThat(filter.isEphemeral("Build succeeded, 42 tests passed")).isTrue();
    }

    @Test
    void substantiveTextIsDurable() {
        assertThat(filter.isEphemeral("You should always reply in Chinese.")).isFalse();
        assertThat(filter.isDurable(text(MsgRole.USER, "The project uses Maven and Java 17."))).isTrue();
        assertThat(filter.isDurable(text(MsgRole.ASSISTANT,
                "Noted: your preferred timezone is UTC+8 for all scheduling."))).isTrue();
    }

    @Test
    void blankAndNullAreNotDurable() {
        assertThat(filter.isDurable(null)).isFalse();
        assertThat(filter.isDurable(text(MsgRole.USER, "   "))).isFalse();
        assertThat(filter.filter(null)).isEmpty();
        assertThat(filter.filter(List.of())).isEmpty();
    }

    @Test
    void ephemeralOnlyChecksFirstLine_soRealAnswersSurvive() {
        // A substantive multi-line answer whose first line is real content stays durable.
        String answer = "Your build tool is Maven.\nI ran the build to confirm.";
        assertThat(filter.isEphemeral(answer)).isFalse();
    }
}
