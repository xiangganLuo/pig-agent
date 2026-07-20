package io.pigagent.cli.repl.command;

import io.pigagent.cli.repl.ReplCommands;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.cli.repl.SubagentSwitchState;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.agent.kernel.ExposedSubagent;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /agent sub list|view <id>|switch <id>|back} — the subagent-online-switch command surface.
 * Offline: a mocked {@link AgentKernel} (owns the exposed-subagent list) + a real, shared
 * {@link SubagentSwitchState} (the pointer the REPL run loop reads). Asserts the command mutates the
 * shared switch state and validates ids against the kernel.
 */
class AgentCommandSubTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private record Harness(CommandLine cmd, SubagentSwitchState switchState, ByteArrayOutputStream out) {
        String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private Harness build(AgentKernel kernel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
        SubagentSwitchState state = new SubagentSwitchState();
        ReplContext ctx = new ReplContext(
                null, kernel, null, null, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null, null, state);
        return new Harness(ReplCommands.build(ctx, CommandLine.defaultFactory()), state, out);
    }

    @Test
    void subList_showsExposedSubagents() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.listSubagents()).thenReturn(List.of(
                new ExposedSubagent("sub-1", "general-purpose", "helper"),
                new ExposedSubagent("sub-2", "reviewer", null)));
        Harness h = build(kernel);

        h.cmd().execute("/agent", "sub", "list");

        assertThat(h.output()).contains("helper").contains("sub-1").contains("reviewer").contains("sub-2");
    }

    @Test
    void subList_empty_showsHint() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.listSubagents()).thenReturn(List.of());
        Harness h = build(kernel);

        h.cmd().execute("/agent", "sub", "list");

        assertThat(h.output()).contains("No exposed subagents");
    }

    @Test
    void subSwitch_knownId_setsSwitchState() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.subagentOutput("sub-1"))
                .thenReturn(Optional.of(new ExposedSubagent("sub-1", "general-purpose", "helper")));
        Harness h = build(kernel);

        h.cmd().execute("/agent", "sub", "switch", "sub-1");

        assertThat(h.switchState().isActive()).isTrue();
        assertThat(h.switchState().current()).isEqualTo("sub-1");
        assertThat(h.output()).contains("Switched into subagent").contains("sub-1");
    }

    @Test
    void subSwitch_unknownId_isRejected_andNotSwitched() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.subagentOutput("nope")).thenReturn(Optional.empty());
        Harness h = build(kernel);

        h.cmd().execute("/agent", "sub", "switch", "nope");

        assertThat(h.switchState().isActive()).isFalse();
        assertThat(h.output()).contains("No such exposed subagent");
    }

    @Test
    void subBack_clearsSwitchState() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        Harness h = build(kernel);
        h.switchState().switchTo("sub-1");

        h.cmd().execute("/agent", "sub", "back");

        assertThat(h.switchState().isActive()).isFalse();
        assertThat(h.output()).contains("Returned to the main agent");
    }

    @Test
    void subView_knownId_showsMetadata() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.subagentOutput("sub-1"))
                .thenReturn(Optional.of(new ExposedSubagent("sub-1", "general-purpose", "helper")));
        Harness h = build(kernel);

        h.cmd().execute("/agent", "sub", "view", "sub-1");

        assertThat(h.output()).contains("helper").contains("general-purpose").contains("sub-1");
    }
}
