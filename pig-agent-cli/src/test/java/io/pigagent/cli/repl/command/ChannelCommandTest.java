package io.pigagent.cli.repl.command;

import io.pigagent.cli.repl.ReplCommands;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig.ChannelConfig;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /channel} command: CRUD over the channels config + safe (credential-free) labels, masked
 * secret entry, and stub refusal on enable/test. Offline — a dumb terminal, a real
 * {@link ConfigurationManager} on a temp file, and a scripted (mock) {@link LineReader}.
 */
class ChannelCommandTest {

    @TempDir
    Path tmp;

    private CommandLine build(ConfigurationManager cfg, LineReader reader, ByteArrayOutputStream out)
            throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .dumb(true)
                .streams(new ByteArrayInputStream(new byte[0]), out)
                .build();
        ReplContext ctx = new ReplContext(
                null, null, null, cfg, null, null, null, null, null, null,
                terminal, new AtomicBoolean(true), new AtomicReference<>(reader), null, null, null);
        return ReplCommands.build(ctx, CommandLine.defaultFactory());
    }

    private ConfigurationManager cfg() {
        return new ConfigurationManager(tmp.resolve("application.yaml"));
    }

    private static void putChannel(ConfigurationManager cfg, String id, ChannelConfig cc) {
        cfg.updateConfig(c -> {
            Map<String, ChannelConfig> m = new LinkedHashMap<>(c.getChannels());
            m.put(id, cc);
            c.setChannels(m);
        });
    }

    private static ChannelConfig dingtalk() {
        ChannelConfig cc = new ChannelConfig();
        cc.setEnabled(true);
        cc.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=SUPER");
        cc.setSignSecret("SIGN-SECRET-VALUE");
        return cc;
    }

    @Test
    void listMarksStubsAndNeverEchoesSecrets() throws IOException {
        ConfigurationManager cfg = cfg();
        putChannel(cfg, "dingtalk", dingtalk());
        ChannelConfig sl = new ChannelConfig();
        sl.setEnabled(true);
        sl.setSigningSecret("SLACK-SECRET-VALUE");
        putChannel(cfg, "slack", sl);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = build(cfg, null, out).execute("/channel", "list");

        String o = out.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(o).contains("dingtalk").contains("slack");
        // Stub is clearly marked; working channel shows a safe hint, never the raw secret/url/token.
        assertThat(o).contains("占位/stub");
        assertThat(o).contains("webhook-url set").contains("secret set");
        assertThat(o).doesNotContain("SUPER").doesNotContain("SIGN-SECRET-VALUE").doesNotContain("SLACK-SECRET-VALUE");
    }

    @Test
    void addPastedWebhookAutoDetectsTypeAndMasksSecret() throws IOException {
        ConfigurationManager cfg = cfg();
        LineReader reader = mock(LineReader.class);
        // Paste a DingTalk webhook → auto-detected (no type pick), then only the masked sign-secret is
        // asked (no port/path). A .invalid host makes the post-add auto-test fail fast + offline.
        when(reader.readLine(anyString())).thenReturn(
                "https://oapi.dingtalk.com.invalid/robot/send?access_token=T");
        when(reader.readLine(anyString(), any(Character.class))).thenReturn("ding-secret");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = build(cfg, reader, out).execute("/channel", "add");

        assertThat(code).isZero();
        ChannelConfig saved = cfg.getConfig().getChannels().get("dingtalk");
        assertThat(saved).isNotNull();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getWebhookUrl()).contains("oapi.dingtalk.com");
        assertThat(saved.getSignSecret()).isEqualTo("ding-secret");
        // No port/path was asked for an outbound robot (they'd be null/0).
        assertThat(saved.getPort()).isZero();
        // The secret was read via the masked overload (readLine(prompt, mask)) — never echoed plainly.
        verify(reader, atLeastOnce()).readLine(contains("加签密钥"), any(Character.class));
        String o = out.toString(StandardCharsets.UTF_8);
        assertThat(o).contains("已识别为").contains("Saved dingtalk");
    }

    @Test
    void addFallsBackToManualPickWhenNoUrlPasted() throws IOException {
        ConfigurationManager cfg = cfg();
        LineReader reader = mock(LineReader.class);
        // Blank webhook paste → numbered picker; "1" = webhook (first functional type), then its port/path.
        when(reader.readLine(anyString())).thenReturn("", "1", "", "");
        when(reader.readLine(anyString(), any(Character.class))).thenReturn(""); // no auth token
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = build(cfg, reader, out).execute("/channel", "add");

        assertThat(code).isZero();
        assertThat(cfg.getConfig().getChannels().containsKey("webhook")).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Saved webhook");
    }

    @Test
    void enableStubIsRefused() throws IOException {
        ConfigurationManager cfg = cfg();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, null, out).execute("/channel", "enable", "slack");

        String o = out.toString(StandardCharsets.UTF_8);
        assertThat(o).contains("占位");
        // A stub must not be flipped on.
        assertThat(cfg.getConfig().getChannels().containsKey("slack")).isFalse();
    }

    @Test
    void enableFunctionalSetsFlag() throws IOException {
        ConfigurationManager cfg = cfg();
        ChannelConfig cc = new ChannelConfig();
        cc.setEnabled(false);
        cc.setWebhookUrl("https://oapi.dingtalk.com/x");
        putChannel(cfg, "dingtalk", cc);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, null, out).execute("/channel", "enable", "dingtalk");

        assertThat(cfg.getConfig().getChannels().get("dingtalk").isEnabled()).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Enabled dingtalk").contains("restart");
    }

    @Test
    void disableSetsFlag() throws IOException {
        ConfigurationManager cfg = cfg();
        putChannel(cfg, "dingtalk", dingtalk());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, null, out).execute("/channel", "disable", "dingtalk");

        assertThat(cfg.getConfig().getChannels().get("dingtalk").isEnabled()).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Disabled dingtalk");
    }

    @Test
    void removeDeletesConfigAfterConfirmation() throws IOException {
        ConfigurationManager cfg = cfg();
        putChannel(cfg, "dingtalk", dingtalk());
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("y");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, reader, out).execute("/channel", "remove", "dingtalk");

        assertThat(cfg.getConfig().getChannels().containsKey("dingtalk")).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Removed dingtalk");
    }

    @Test
    void testStubIsRefusedWithoutNetwork() throws IOException {
        ConfigurationManager cfg = cfg();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, null, out).execute("/channel", "test", "slack");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("占位");
    }

    @Test
    void testRobotWithoutWebhookUrlReportsMissingConfig() throws IOException {
        ConfigurationManager cfg = cfg();
        ChannelConfig cc = new ChannelConfig();
        cc.setEnabled(true); // no webhook-url → nothing to dispatch, no network
        putChannel(cfg, "dingtalk", cc);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, null, out).execute("/channel", "test", "dingtalk");

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("No outbound webhook-url");
    }

    @Test
    void listOnEmptyConfigStillShowsWorkingOptions() throws IOException {
        ConfigurationManager cfg = cfg();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        build(cfg, null, out).execute("/channel", "list");

        String o = out.toString(StandardCharsets.UTF_8);
        assertThat(o).contains("none").contains("Working:").contains("dingtalk").contains("feishu");
    }
}
