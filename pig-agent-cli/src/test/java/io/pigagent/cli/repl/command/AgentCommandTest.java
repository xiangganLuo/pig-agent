package io.pigagent.cli.repl.command;

import io.pigagent.cli.repl.ReplCommands;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /agent new} creation grammar: plain form stays interactive; the autonomous flags
 * ({@code --mandate}/{@code --schedule}/{@code --allow}) build a digital-employee {@link AgentSpec}
 * through the {@link AgentKernel} façade; a bad cron and a schedule-without-mandate are rejected
 * with a friendly line and no create. Offline (mock kernel + dumb terminal).
 */
class AgentCommandTest {

    private CommandLine build(AgentKernel kernel, ByteArrayOutputStream out) throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
        ReplContext ctx = new ReplContext(
                null, kernel, null, null, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null, null);
        return ReplCommands.build(ctx, CommandLine.defaultFactory());
    }

    @Test
    void newWithMandateScheduleAllow_buildsAutonomousSpec() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("emp")).thenReturn(Optional.empty());
        ArgumentCaptor<AgentSpec> cap = ArgumentCaptor.forClass(AgentSpec.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(kernel, out).execute("/agent", "new", "emp", "Night Watch",
                "--mandate", "check CI nightly", "--schedule", "0 2 * * *",
                "--allow", "mvn test,git status");

        verify(kernel).createAgent(cap.capture());
        AgentSpec spec = cap.getValue();
        assertThat(spec.id()).isEqualTo("emp");
        assertThat(spec.name()).isEqualTo("Night Watch");
        assertThat(spec.isAutonomous()).isTrue();
        assertThat(spec.schedule()).isEqualTo("0 2 * * *");
        assertThat(spec.mandate()).isEqualTo("check CI nightly");
        assertThat(spec.commandAllowlist()).containsExactly("mvn test", "git status");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("digital employee");
    }

    @Test
    void newWithBadCron_isRejected_andNotCreated() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("bad")).thenReturn(Optional.empty());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(kernel, out).execute("/agent", "new", "bad", "Bad",
                "--mandate", "do stuff", "--schedule", "not-a-cron");

        verify(kernel, never()).createAgent(any());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Invalid cron");
    }

    @Test
    void scheduleWithoutMandate_isRejected_andNotCreated() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("x")).thenReturn(Optional.empty());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(kernel, out).execute("/agent", "new", "x", "X", "--schedule", "0 2 * * *");

        verify(kernel, never()).createAgent(any());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("mandate");
    }

    @Test
    void plainNew_isUnchanged_interactiveSpec() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("a")).thenReturn(Optional.empty());
        ArgumentCaptor<AgentSpec> cap = ArgumentCaptor.forClass(AgentSpec.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(kernel, out).execute("/agent", "new", "a", "Assistant");

        verify(kernel).createAgent(cap.capture());
        AgentSpec spec = cap.getValue();
        assertThat(spec.id()).isEqualTo("a");
        assertThat(spec.name()).isEqualTo("Assistant");
        assertThat(spec.isAutonomous()).isFalse();
        assertThat(spec.mandate()).isNull();
        assertThat(spec.commandAllowlist()).isEmpty();
    }

    @Test
    void newWithExistingId_isRejected() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("dup")).thenReturn(Optional.of(mock(io.pigagent.core.agent.AgentInstance.class)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(kernel, out).execute("/agent", "new", "dup", "Dup");

        verify(kernel, never()).createAgent(any());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("already exists");
    }
}
