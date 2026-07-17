package io.pigagent.cli.repl;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /notify} command: test-send calls the service; status shows config without the recipient. */
class NotifyCommandTest {

    @TempDir
    Path tmp;

    private static final class RecordingService implements NotificationService {
        final List<Notification> notified = new ArrayList<>();
        NotificationResult result = NotificationResult.delivered("sent via feishu");

        @Override
        public NotificationResult notify(Notification notification) {
            notified.add(notification);
            return result;
        }
    }

    private CommandLine build(ConfigurationManager cfg, NotificationService svc,
                             ByteArrayOutputStream out) throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        ReplContext ctx = new ReplContext(
                null, null, null, cfg, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<LineReader>(), null, svc, null);
        return ReplCommands.build(ctx, CommandLine.defaultFactory());
    }

    /** Enable outreach with a channel + recipient so {@code /notify test} passes the pre-check. */
    private static ConfigurationManager configuredOutreach(Path yaml) {
        ConfigurationManager cfg = new ConfigurationManager(yaml);
        cfg.updateConfig(c -> {
            c.getOutreach().setEnabled(true);
            c.getOutreach().setChannel("feishu");
            c.getOutreach().setRecipient("some-recipient");
        });
        return cfg;
    }

    @Test
    void testActionSendsThroughService() throws IOException {
        ConfigurationManager cfg = configuredOutreach(tmp.resolve("application.yaml"));
        RecordingService svc = new RecordingService();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = build(cfg, svc, out).execute("/notify", "test", "hello", "world");

        assertThat(code).isZero();
        assertThat(svc.notified).hasSize(1);
        assertThat(svc.notified.get(0).body()).isEqualTo("hello world");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Sent");
    }

    @Test
    void testRefusedWhenOutreachDisabled() throws IOException {
        // Default config → outreach disabled: the pre-check refuses before touching the service.
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        RecordingService svc = new RecordingService();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, svc, out).execute("/notify", "test");

        assertThat(svc.notified).isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("disabled");
    }

    @Test
    void testRefusedWhenNoRecipientConfigured() throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        cfg.updateConfig(c -> {
            c.getOutreach().setEnabled(true);
            c.getOutreach().setChannel("feishu");
            // recipient left blank on purpose
        });
        RecordingService svc = new RecordingService();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, svc, out).execute("/notify", "test");

        assertThat(svc.notified).isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("channel/recipient");
    }

    @Test
    void statusShowsConfigWithoutRecipientValue() throws IOException {
        ConfigurationManager cfg = new ConfigurationManager(tmp.resolve("application.yaml"));
        cfg.updateConfig(c -> {
            c.getOutreach().setEnabled(true);
            c.getOutreach().setChannel("feishu");
            c.getOutreach().setRecipient("super-secret-token");
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = build(cfg, new RecordingService(), out).execute("/notify", "status");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(output).contains("Outreach").contains("feishu").contains("Enabled");
        // The recipient value MUST NOT be shown — only that one is configured.
        assertThat(output).doesNotContain("super-secret-token");
        assertThat(output).contains("(set)");
    }

    @Test
    void suppressedResultShownAsNotSent() throws IOException {
        // Outreach configured so the pre-check passes; the service then reports a guardrail suppression.
        ConfigurationManager cfg = configuredOutreach(tmp.resolve("application.yaml"));
        RecordingService svc = new RecordingService();
        svc.result = NotificationResult.of(NotificationResult.Outcome.QUIET_HOURS, "quiet hours");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, svc, out).execute("/notify", "test");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Not sent").contains("QUIET_HOURS");
    }
}
