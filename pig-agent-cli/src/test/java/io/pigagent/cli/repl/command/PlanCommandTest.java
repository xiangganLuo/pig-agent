package io.pigagent.cli.repl.command;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.cli.repl.ReplCommands;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.PlanModeSettings;
import io.pigagent.session.SessionManager;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /plan} command: status reflects config + active state; {@code enter} is gated on
 * {@code plan-mode.enabled} (refuses when disabled) and actually enters the read-only plan phase
 * when enabled; {@code exit} leaves it. Offline (fake model + {@code @TempDir}).
 */
class PlanCommandTest {

    private static final String SID = "s1";

    /** Fake model that always answers with text (no turns are run here — only the plan API). */
    static final class TextModel implements Model {
        @Override public String getModelName() { return "fake-text"; }
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("ok").build())).finishReason("stop").build());
        }
    }

    private PigAgent planAgent(Path ws) {
        return PigAgent.builder()
                .name("planner").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit()).workspace(ws)
                .planMode(new PlanModeSettings(true, "plans", false))
                .build();
    }

    private CommandLine build(ConfigurationManager cfg, PigAgent agent, ByteArrayOutputStream out)
            throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true).streams(new ByteArrayInputStream(new byte[0]), out).build();
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.getCurrentSessionId()).thenReturn(SID);
        ReplContext ctx = new ReplContext(
                new AgentHolder(agent), null, null, cfg, null, null, null, null, null, sessions,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, null);
        return ReplCommands.build(ctx, CommandLine.defaultFactory());
    }

    @Test
    void statusShowsEnabledAndActiveState(@TempDir Path tmp) throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        cfg.updateConfig(c -> c.getPlanMode().setEnabled(true));
        PigAgent agent = planAgent(tmp.resolve("ws"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = build(cfg, agent, out).execute("/plan", "status");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(output).contains("Plan Mode").contains("Enabled").contains("on");
        agent.close();
    }

    @Test
    void enterRefusedWhenConfigDisabled(@TempDir Path tmp) throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        // plan-mode defaults to disabled → /plan enter must refuse and not enter.
        PigAgent agent = planAgent(tmp.resolve("ws"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, agent, out).execute("/plan", "enter");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("disabled");
        assertThat(agent.isPlanModeActive(SID)).as("a refused enter does not enter Plan Mode").isFalse();
        agent.close();
    }

    @Test
    void enterAndExitDrivePlanState(@TempDir Path tmp) throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        cfg.updateConfig(c -> c.getPlanMode().setEnabled(true));
        PigAgent agent = planAgent(tmp.resolve("ws"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine cli = build(cfg, agent, out);

        cli.execute("/plan", "enter");
        assertThat(agent.isPlanModeActive(SID)).as("/plan enter activates Plan Mode").isTrue();

        cli.execute("/plan", "exit");
        assertThat(agent.isPlanModeActive(SID)).as("/plan exit deactivates Plan Mode").isFalse();

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Entered Plan Mode").contains("Exited Plan Mode");
        agent.close();
    }
}
