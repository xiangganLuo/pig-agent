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
 * {@code /agent} model validation ({@code model}/{@code new} refuse / recognise a modelId against the
 * saved models, mirroring {@code /model}) and multi-word name handling for {@code new}. Offline: a
 * mocked {@link AgentKernel} + {@link ModelManager} and a dumb terminal.
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

    private AgentInstance instance(String id) {
        return new AgentInstance(id, AgentSpec.create(id, "Bot"), mock(PigAgent.class));
    }

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
}
