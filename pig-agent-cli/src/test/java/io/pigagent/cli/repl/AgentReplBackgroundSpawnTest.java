package io.pigagent.cli.repl;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3 background-subagent visibility: a background {@code agent_spawn} (one returning a {@code task_id},
 * status often {@code timeout_promoted}) must surface a clear "dispatched, will report" running-notice
 * instead of a silent hang or a raw JSON dump. A synchronous spawn still renders its normal result.
 */
class AgentReplBackgroundSpawnTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static Terminal dumbTerminal(ByteArrayOutputStream out) throws IOException {
        return TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
    }

    private static AgentRepl repl() {
        return new AgentRepl(null, null, null, null, null, null, null, null, null, null,
                null, new AtomicReference<>(), null, null, null);
    }

    @Test
    void backgroundSpawnResultRendersRunningNotice() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);

        AgentEvent delta = new ToolResultTextDeltaEvent("r1", "tc1", "agent_spawn",
                "{\"task_id\":\"abcd1234efgh\",\"status\":\"timeout_promoted\"}");
        AgentEvent end = new ToolResultEndEvent("r1", "tc1", "agent_spawn", ToolResultState.SUCCESS);

        repl().renderStream(Flux.just(delta, end), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("agent_spawn");             // the tool head still prints
        assertThat(printed).contains("已派发后台子agent").contains("运行中"); // running notice
        assertThat(printed).contains("abcd1234");                // short (first-8) task id
        assertThat(printed).doesNotContain("timeout_promoted");  // raw JSON status is not dumped
    }

    @Test
    void backgroundTaskIdParsesFirstEightChars() {
        assertThat(AgentRepl.backgroundTaskId("{\"task_id\": \"abcdefgh12345\"}")).isEqualTo("abcdefgh");
        assertThat(AgentRepl.backgroundTaskId("task_id=xyz")).isEqualTo("xyz");
        assertThat(AgentRepl.backgroundTaskId("nothing here")).isEmpty();
        assertThat(AgentRepl.backgroundTaskId(null)).isEmpty();
    }

    @Test
    void synchronousSpawnResultRendersNormalBody() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);

        // A synchronous spawn returns the child's actual result (no task_id / timeout_promoted).
        AgentEvent delta = new ToolResultTextDeltaEvent("r1", "tc1", "agent_spawn",
                "review complete: 2 issues found");
        AgentEvent end = new ToolResultEndEvent("r1", "tc1", "agent_spawn", ToolResultState.SUCCESS);

        repl().renderStream(Flux.just(delta, end), terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("review complete: 2 issues found");
        assertThat(printed).doesNotContain("已派发后台子agent");
    }
}
