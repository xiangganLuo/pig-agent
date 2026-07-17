package io.pigagent.cli.repl;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F2 session-entry replay wiring: both the REPL startup path ({@code maybeReplayCurrentSession}) and
 * {@code /session switch} must replay the target session slot's recent history so a resumed populated
 * session isn't a blank screen. The conversation is reached through the active agent's
 * {@code getMemory(sessionId)} view (mocked/seeded here).
 */
class AgentReplReplayTest {

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

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg assistant(String text) {
        return Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static AgentHolder holderWithMemory(String sessionId, List<Msg> messages) {
        AgentHolder holder = mock(AgentHolder.class);
        PigAgent agent = mock(PigAgent.class);
        Memory memory = mock(Memory.class);
        when(holder.get()).thenReturn(agent);
        when(agent.getMemory(sessionId)).thenReturn(memory);
        when(memory.getMessages()).thenReturn(messages);
        return holder;
    }

    @Test
    void startupReplaysCurrentSessionTail() throws IOException {
        AgentHolder holder = holderWithMemory("s1",
                List.of(user("earlier question"), assistant("earlier answer")));
        SessionManager sm = mock(SessionManager.class);
        when(sm.getCurrentSessionId()).thenReturn("s1");
        when(sm.getCurrentSession()).thenReturn(Optional.empty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(holder, null, null, null, null, null, null, null, null,
                sm, null, new AtomicReference<>(), null, null, null);

        repl.maybeReplayCurrentSession(terminal);

        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("已恢复会话");
        assertThat(printed).contains("earlier question").contains("earlier answer");
    }

    @Test
    void startupReplayRendersNothingForEmptySession() throws IOException {
        AgentHolder holder = holderWithMemory("s1", List.of());
        SessionManager sm = mock(SessionManager.class);
        when(sm.getCurrentSessionId()).thenReturn("s1");
        when(sm.getCurrentSession()).thenReturn(Optional.empty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        AgentRepl repl = new AgentRepl(holder, null, null, null, null, null, null, null, null,
                sm, null, new AtomicReference<>(), null, null, null);

        repl.maybeReplayCurrentSession(terminal);

        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("已恢复会话");
    }

    @Test
    void startupReplayNeverThrowsWhenAgentMissing() throws IOException {
        SessionManager sm = mock(SessionManager.class);
        when(sm.getCurrentSessionId()).thenReturn("s1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        // Null agentHolder → the method must degrade silently (best-effort, never breaks startup).
        AgentRepl repl = new AgentRepl(null, null, null, null, null, null, null, null, null,
                sm, null, new AtomicReference<>(), null, null, null);

        repl.maybeReplayCurrentSession(terminal); // no throw

        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("已恢复会话");
    }

    @Test
    void sessionSwitchReplaysTargetSlot() throws IOException {
        Session target = new Session("s2", "Target", Instant.now(), Instant.now(),
                null, null, null, false);
        SessionManager sm = mock(SessionManager.class);
        when(sm.list()).thenReturn(List.of(target));
        when(sm.getCurrentSession()).thenReturn(Optional.of(target));
        AgentHolder holder = holderWithMemory("s2",
                List.of(user("target question"), assistant("target answer")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = dumbTerminal(out);
        ReplContext ctx = new ReplContext(holder, null, null, null, null, null, null, null, null,
                sm, terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null, null);
        CommandLine cmd = ReplCommands.build(ctx, CommandLine.defaultFactory());

        int code = cmd.execute("/session", "switch", "s2");

        assertThat(code).isZero();
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("Switched to");
        assertThat(printed).contains("已恢复会话");
        assertThat(printed).contains("target question").contains("target answer");
    }
}
