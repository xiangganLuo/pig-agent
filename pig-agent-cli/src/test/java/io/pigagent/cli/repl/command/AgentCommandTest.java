package io.pigagent.cli.repl.command;

import io.pigagent.cli.repl.ReplCommands;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /agent} command tests. Two concerns merged here:
 * <ul>
 *   <li><b>model validation + multi-word name</b> ({@code model}/{@code new} recognise/refuse a
 *       modelId against the saved models, mirroring {@code /model}; a trailing saved-model token is
 *       the model, else part of an unquoted multi-word name);</li>
 *   <li><b>digital-employee creation grammar</b> ({@code --mandate}/{@code --schedule}/{@code --allow}
 *       build an autonomous {@link AgentSpec}; plain {@code new} stays interactive; a bad cron and a
 *       schedule-without-mandate are rejected with a friendly line and no create).</li>
 * </ul>
 * Offline: a mocked {@link AgentKernel} (+ {@link ModelManager} where needed) and a dumb terminal.
 */
class AgentCommandTest {

    private record Harness(CommandLine cmd, AgentKernel kernel, ByteArrayOutputStream out) {
        String output() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private Harness build(ModelManager mm) throws IOException {
        return build(mm, mock(AgentKernel.class));
    }

    private Harness build(ModelManager mm, AgentKernel kernel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
        ReplContext ctx = new ReplContext(
                null, kernel, null, null, null, mm, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null, null);
        return new Harness(ReplCommands.build(ctx, CommandLine.defaultFactory()), kernel, out);
    }

    /** Autonomous-flag tests don't need a ModelManager; build a kernel-only harness (null mm). */
    private CommandLine build(AgentKernel kernel, ByteArrayOutputStream out) throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
        ReplContext ctx = new ReplContext(
                null, kernel, null, null, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null, null);
        return ReplCommands.build(ctx, CommandLine.defaultFactory());
    }

    private AgentInstance instance(String id) {
        return new AgentInstance(id, AgentSpec.create(id, "Bot"), mock(PigAgent.class));
    }

    // ---- model validation + multi-word name ----

    @Test
    void modelRefusesBogusModelIdAndDoesNotUpdate() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("bot")).thenReturn(Optional.of(instance("bot")));
        ModelManager mm = mock(ModelManager.class);
        when(mm.findById("nope")).thenReturn(Optional.empty());
        when(mm.list()).thenReturn(List.of(StoredModel.create("openai", "k", null, "gpt-4")));
        Harness h = build(mm, kernel);

        int code = h.cmd().execute("/agent", "model", "bot", "nope");

        assertThat(code).isZero();
        assertThat(h.output()).contains("No such model").contains("nope");
        verify(kernel, never()).updateAgent(any());
    }

    @Test
    void modelAppliesValidModelId() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        AgentInstance inst = instance("bot");
        when(kernel.getAgent("bot")).thenReturn(Optional.of(inst));
        StoredModel good = StoredModel.create("openai", "k", null, "gpt-4o");
        ModelManager mm = mock(ModelManager.class);
        when(mm.findById(good.id())).thenReturn(Optional.of(good));
        Harness h = build(mm, kernel);

        int code = h.cmd().execute("/agent", "model", "bot", good.id());

        assertThat(code).isZero();
        assertThat(h.output()).contains("now uses model").contains(good.id());
        verify(kernel).updateAgent(any());
    }

    @Test
    void newKeepsUnquotedMultiWordName() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("bot")).thenReturn(Optional.empty());
        when(kernel.createAgent(any())).thenReturn(instance("bot"));
        ModelManager mm = mock(ModelManager.class);
        when(mm.findById(anyString())).thenReturn(Optional.empty()); // no trailing token is a saved model
        Harness h = build(mm, kernel);

        int code = h.cmd().execute("/agent", "new", "bot", "My", "Cool", "Bot");

        assertThat(code).isZero();
        ArgumentCaptor<AgentSpec> cap = ArgumentCaptor.forClass(AgentSpec.class);
        verify(kernel).createAgent(cap.capture());
        assertThat(cap.getValue().name()).isEqualTo("My Cool Bot");
        assertThat(cap.getValue().modelId()).isNull();
    }

    @Test
    void newRecognisesTrailingSavedModelId() throws IOException {
        AgentKernel kernel = mock(AgentKernel.class);
        when(kernel.getAgent("bot")).thenReturn(Optional.empty());
        when(kernel.createAgent(any())).thenReturn(instance("bot"));
        StoredModel good = StoredModel.create("openai", "k", null, "gpt-4o");
        ModelManager mm = mock(ModelManager.class);
        when(mm.findById(good.id())).thenReturn(Optional.of(good));
        Harness h = build(mm, kernel);

        int code = h.cmd().execute("/agent", "new", "bot", "Cool", "Bot", good.id());

        assertThat(code).isZero();
        ArgumentCaptor<AgentSpec> cap = ArgumentCaptor.forClass(AgentSpec.class);
        verify(kernel).createAgent(cap.capture());
        assertThat(cap.getValue().name()).isEqualTo("Cool Bot");
        assertThat(cap.getValue().modelId()).isEqualTo(good.id());
    }

    // ---- digital-employee creation grammar ----

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
        when(kernel.getAgent("dup")).thenReturn(Optional.of(mock(AgentInstance.class)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(kernel, out).execute("/agent", "new", "dup", "Dup");

        verify(kernel, never()).createAgent(any());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("already exists");
    }
}
