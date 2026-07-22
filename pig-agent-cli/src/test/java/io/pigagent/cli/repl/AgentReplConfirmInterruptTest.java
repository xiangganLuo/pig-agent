package io.pigagent.cli.repl;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ctrl-C / Ctrl-D at the HITL confirm prompt (default {@code ask} mode) MUST NOT surface as a
 * terminal exception stack (fix #3). The confirm loop catches JLine's {@link UserInterruptException}
 * / {@link EndOfFileException}, shows a friendly {@code [已中断]}, and fails closed by denying the
 * current tool call and every remaining one.
 */
class AgentReplConfirmInterruptTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static Terminal dumbTerminal(ByteArrayOutputStream out) throws IOException {
        return TerminalBuilder.builder().dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out).build();
    }

    private static AgentRepl repl(AtomicReference<LineReader> readerRef) {
        return new AgentRepl(null, mock(AgentKernel.class), null, null, null, null, null, null, null,
                null, null, readerRef, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<ConfirmResult> invokeConfirm(AgentRepl repl, RequireUserConfirmEvent ask,
                                                     Terminal terminal) throws Exception {
        Method m = AgentRepl.class.getDeclaredMethod("confirm", RequireUserConfirmEvent.class, Terminal.class);
        m.setAccessible(true);
        return (List<ConfirmResult>) m.invoke(repl, ask, terminal);
    }

    private static RequireUserConfirmEvent ask(String... toolNames) {
        List<ToolUseBlock> calls = java.util.Arrays.stream(toolNames)
                .map(n -> ToolUseBlock.builder().id("c-" + n).name(n).input(Map.of()).content("{}").build())
                .toList();
        return new RequireUserConfirmEvent("r1", calls);
    }

    @Test
    void ctrlCAtConfirmPrompt_deniesAllAndDoesNotThrow() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenThrow(new UserInterruptException(""));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AgentRepl repl = repl(new AtomicReference<>(reader));
        List<ConfirmResult> results = invokeConfirm(repl, ask("writeFile", "executeCommand"), dumbTerminal(out));

        assertThat(results).as("current + remaining tool calls all denied").hasSize(2);
        assertThat(results).allMatch(cr -> !cr.isConfirmed());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("[已中断]");
    }

    @Test
    void ctrlDAtConfirmPrompt_deniesAllAndDoesNotThrow() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenThrow(new EndOfFileException(""));

        AgentRepl repl = repl(new AtomicReference<>(reader));
        List<ConfirmResult> results = invokeConfirm(repl, ask("writeFile"), dumbTerminal(new ByteArrayOutputStream()));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isFalse();
    }
}
