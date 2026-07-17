package io.pigagent.cli.repl;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.session.SessionManager;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REPL confirm loop's {@code a} (always allow) branch (change {@code permission-always-allow-persist}).
 * Verifies the three-layer wiring: (1) the returned {@link ConfirmResult} carries an ALLOW rule for the
 * tool (native applyConfirmResults, this invocation); (2) the kernel façade
 * {@link AgentKernel#allowToolForSession} is invoked with (activeId, currentSessionId, toolName) to
 * persist the next-turn ASK→ALLOW swap; (3) {@code y}/{@code N} keep their prior no-rule semantics and
 * never call the façade. {@code confirm()} is package-private-tested via reflection (it is private).
 */
class AgentReplConfirmAlwaysTest {

    private static Terminal dumbTerminal() throws IOException {
        return TerminalBuilder.builder().dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).build();
    }

    private static AgentRepl repl(AgentKernel kernel, ConfigurationManager cfg, SessionManager sessions,
                                  AtomicReference<LineReader> readerRef) {
        // Positions: (0) agentHolder, (1) agentKernel, (3) configManager, (9) sessionManager, (11) readerRef.
        return new AgentRepl(null, kernel, null, cfg, null, null, null, null, null,
                sessions, null, readerRef, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<ConfirmResult> invokeConfirm(AgentRepl repl, RequireUserConfirmEvent ask,
                                                     Terminal terminal) throws Exception {
        Method m = AgentRepl.class.getDeclaredMethod("confirm", RequireUserConfirmEvent.class, Terminal.class);
        m.setAccessible(true);
        return (List<ConfirmResult>) m.invoke(repl, ask, terminal);
    }

    private static RequireUserConfirmEvent ask(String toolName) {
        ToolUseBlock call = ToolUseBlock.builder().id("c1").name(toolName).input(Map.of()).content("{}").build();
        return new RequireUserConfirmEvent("r1", List.of(call));
    }

    @Test
    void alwaysBranch_attachesAllowRule_andPersistsViaKernel() throws Exception {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        ConfigurationManager cfg = mock(ConfigurationManager.class); // updateConfig no-op → rememberTool safe
        when(kernel.activeId()).thenReturn("default");
        when(sessions.getCurrentSessionId()).thenReturn("s1");
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("a");

        AgentRepl repl = repl(kernel, cfg, sessions, new AtomicReference<>(reader));
        List<ConfirmResult> results = invokeConfirm(repl, ask("updateProfile"), dumbTerminal());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(0).getRules())
                .as("the ConfirmResult carries an ALLOW rule for the tool")
                .anyMatch(r -> r.toolName().equals("updateProfile")
                        && r.behavior() == PermissionBehavior.ALLOW);
        verify(kernel).allowToolForSession("default", "s1", "updateProfile");
    }

    @Test
    void yesBranch_confirmsWithoutRule_andDoesNotPersist() throws Exception {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        ConfigurationManager cfg = mock(ConfigurationManager.class);
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("y");

        AgentRepl repl = repl(kernel, cfg, sessions, new AtomicReference<>(reader));
        List<ConfirmResult> results = invokeConfirm(repl, ask("writeFile"), dumbTerminal());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isTrue();
        assertThat(results.get(0).getRules()).as("'y' attaches no rule").isNullOrEmpty();
        verify(kernel, never()).allowToolForSession(anyString(), anyString(), anyString());
    }

    @Test
    void denyBranch_notConfirmed_andDoesNotPersist() throws Exception {
        AgentKernel kernel = mock(AgentKernel.class);
        SessionManager sessions = mock(SessionManager.class);
        ConfigurationManager cfg = mock(ConfigurationManager.class);
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("N");

        AgentRepl repl = repl(kernel, cfg, sessions, new AtomicReference<>(reader));
        List<ConfirmResult> results = invokeConfirm(repl, ask("executeCommand"), dumbTerminal());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isConfirmed()).isFalse();
        verify(kernel, never()).allowToolForSession(anyString(), anyString(), anyString());
    }
}
