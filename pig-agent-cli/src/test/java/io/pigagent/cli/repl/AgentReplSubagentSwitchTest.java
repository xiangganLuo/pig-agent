package io.pigagent.cli.repl;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.agent.kernel.ExposedSubagent;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.session.SessionManager;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * subagent-online-switch — the REPL side: (1) a {@link SubagentExposedEvent} on the stream is tracked
 * on the kernel + surfaced with a switch hint; (2) while switched into a subagent, {@code runTurn}
 * routes to {@code chatWithSubagent} (NOT the parent {@code chat}, no parent session note/compress);
 * (3) a stale/vanished subagent falls back to the parent.
 */
class AgentReplSubagentSwitchTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static Terminal dumbTerminal(ByteArrayOutputStream out) throws IOException {
        return TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
    }

    private AgentRepl repl(AgentKernel kernel, SessionManager sessions, CompressionService compression) {
        return new AgentRepl(null, kernel, null, null, null, null, compression, null, null,
                sessions, null, new AtomicReference<>(), null, null, null);
    }

    @Test
    void subagentExposedEvent_isTrackedOnKernel_withSwitchHint() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = repl(kernel, null, null);

        AgentEvent exposed = new SubagentExposedEvent("sub-9", "reviewer", "sess", "the reviewer");
        repl.renderStream(Flux.just(exposed), terminal);

        verify(kernel).noteSubagentExposed("sub-9", "reviewer", "the reviewer");
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("subagent exposed").contains("the reviewer")
                .contains("/agent sub switch sub-9");
    }

    @Test
    void runTurn_whileSwitched_routesToSubagent_notParent() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        CompressionService compression = mock(CompressionService.class);
        when(kernel.subagentOutput("sub-1"))
                .thenReturn(Optional.of(new ExposedSubagent("sub-1", "gp", "helper")));
        when(kernel.chatWithSubagent(eq("sub-1"), any(Msg.class)))
                .thenReturn(Flux.just(new TextBlockDeltaEvent("r", "b", "child says hi\n")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = repl(kernel, sessions, compression);
        repl.subagentSwitch().switchTo("sub-1");

        repl.runTurn("hello sub", terminal);

        verify(kernel).chatWithSubagent(eq("sub-1"), any(Msg.class));
        verify(kernel, never()).chat(anyString(), any(Msg.class), any());
        verify(sessions, never()).noteUserMessage(anyString()); // subagent turn ≠ parent session
        verify(compression, never()).maybeCompress(any());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("child says hi");
    }

    @Test
    void runTurn_switchedButSubagentGone_fallsBackToParent() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        CompressionService compression = mock(CompressionService.class);
        when(kernel.subagentOutput("gone")).thenReturn(Optional.empty()); // vanished (agent switch)
        when(kernel.activeId()).thenReturn("default");
        when(sessions.getCurrentSessionId()).thenReturn("s1");
        when(kernel.chat(eq("default"), any(Msg.class), eq("s1")))
                .thenReturn(Flux.just(new TextBlockDeltaEvent("r", "b", "parent handled it\n")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = repl(kernel, sessions, compression);
        repl.subagentSwitch().switchTo("gone");

        repl.runTurn("still here?", terminal);

        assertThat(repl.subagentSwitch().isActive()).as("stale switch cleared").isFalse();
        verify(kernel).chat(eq("default"), any(Msg.class), eq("s1")); // fell back to the parent
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("不可用").contains("parent handled it");
    }
}
