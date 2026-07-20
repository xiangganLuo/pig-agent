package io.pigagent.cli.repl.command;

import io.pigagent.channel.Channel;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.ChannelType;
import io.pigagent.channel.outreach.OutboundChannel;
import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.config.PigAgentConfig.ChannelConfig;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /channel} — manage messaging channels at runtime (list/add/remove/enable/disable/test).
 *
 * <p>Operator-facing, mirroring {@code /mcp}: it CRUDs the {@code channels.<id>} config through
 * {@link io.pigagent.config.ConfigurationManager} (persisted to {@code application.yaml}) and reads
 * live running-state from the started bridges ({@link ReplContext#bridges()}). Secrets are entered via
 * JLine masked input and are <b>never</b> echoed — {@code list} shows only a safe label (transport +
 * non-secret hints, e.g. "webhook-url set"), never a token / URL / signing secret.
 *
 * <p><b>Honesty:</b> Slack (whose outbound can't reply) is a non-functional stub
 * ({@link ChannelType#isFunctional()}). {@code list} marks it clearly and {@code enable} /
 * {@code test} refuse it with "该渠道尚未实现（占位）" rather than pretending it connects. The working
 * channels are DingTalk / Feishu / Webhook / Stdin.
 *
 * <p><b>Startup:</b> channels are started at launch by {@code PigAgentCli.startChannels} for every
 * <em>enabled + functional</em> channel, independent of the opt-in native {@code channel-gateway}
 * kernel flag. So {@code enable} persists the flag and tells the user the channel connects on the next
 * launch (a mid-session transport start is out of scope here).
 */
@Command(name = "/channel", description = "Manage channels (list|add|remove|enable|disable|test)")
public final class ChannelCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "list | add | remove | enable | disable | test")
    String action;

    @Parameters(index = "1..*", paramLabel = "<args>")
    String[] args;

    public ChannelCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        String act = action == null ? "list" : action.toLowerCase();
        switch (act) {
            case "list" -> listChannels(t);
            case "add" -> addChannel(t);
            case "remove", "delete" -> removeChannel(t);
            case "enable" -> toggleChannel(t, true);
            case "disable" -> toggleChannel(t, false);
            case "test" -> testChannel(t);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private void listChannels(Terminal t) {
        Map<String, ChannelConfig> configs = channels();
        Ansi.println(t, Ansi.heading("Channels:"));
        if (configs.isEmpty()) {
            Ansi.println(t, Ansi.dim("  (none — use /channel add)"));
        } else {
            int idx = 1;
            for (Map.Entry<String, ChannelConfig> e : configs.entrySet()) {
                printChannelRow(t, idx++, e.getKey(), e.getValue());
            }
        }
        Ansi.println(t, Ansi.dim("  Working: " + String.join(", ", functionalIds())
                + "   (stubs: " + String.join(", ", stubIds()) + ")"));
    }

    private void printChannelRow(Terminal t, int idx, String id, ChannelConfig cc) {
        ChannelType type = ChannelType.fromId(id).orElse(null);
        String name = type != null ? type.displayName() : "unknown adapter";
        Ansi.println(t, String.format("  %2d) ", idx) + Ansi.info(id)
                + Ansi.dim(" [" + name + "] " + connectionHint(cc) + "  ") + statusOf(id, type, cc));
    }

    /** Colored status: unknown / stub / running / configured-not-running / disabled. */
    private String statusOf(String id, ChannelType type, ChannelConfig cc) {
        if (type == null) {
            return Ansi.error("unknown adapter");
        }
        if (!type.isFunctional()) {
            return Ansi.warn("占位/stub — not functional yet");
        }
        if (isRunning(id)) {
            return Ansi.success("running");
        }
        return cc.isEnabled() ? Ansi.warn("enabled — restart to connect") : Ansi.dim("disabled");
    }

    private void addChannel(Terminal t) {
        LineReader reader = ctx.readerRef().get();
        if (reader == null) {
            Ansi.println(t, Ansi.error("Interactive input is unavailable."));
            return;
        }
        Ansi.println(t, Ansi.heading("Add channel"));
        Ansi.println(t, Ansi.dim("  粘贴机器人 Webhook 地址可自动识别（钉钉/飞书）；或直接回车手动选类型。"));
        String pasted = reader.readLine("Webhook 地址（可留空）: ").trim();
        ChannelType type = detectType(pasted);
        String prefillUrl = null;
        if (type != null) {
            prefillUrl = pasted;
            Ansi.println(t, Ansi.success("已识别为 " + type.displayName() + "。"));
        } else {
            if (!pasted.isBlank()) {
                Ansi.println(t, Ansi.warn("无法从该地址识别渠道，改为手动选择。"));
            }
            type = pickType(reader, t);
            if (type == null) {
                Ansi.println(t, Ansi.error("Invalid selection."));
                return;
            }
        }
        printSetupHint(t, type);
        ChannelConfig cc = readConfig(reader, type, prefillUrl);
        cc.setEnabled(true);
        putChannel(type.id(), cc);
        Ansi.println(t, Ansi.success("Saved " + type.id() + " (enabled). ")
                + Ansi.dim("Connects on next launch — restart to activate."));
        autoTest(t, type, cc);
    }

    /** Auto-detect a robot channel from a pasted webhook URL (host keyword); null if unrecognized/blank. */
    private static ChannelType detectType(String url) {
        if (url == null || url.isBlank() || !url.toLowerCase().startsWith("http")) {
            return null;
        }
        String u = url.toLowerCase();
        if (u.contains("dingtalk")) {
            return ChannelType.DINGTALK;
        }
        if (u.contains("feishu") || u.contains("larksuite")) {
            return ChannelType.FEISHU;
        }
        return null;
    }

    /** The numbered type picker — fallback when no URL was pasted / it wasn't recognized. Null on bad input. */
    private ChannelType pickType(LineReader reader, Terminal t) {
        List<ChannelType> options = functionalTypes();
        Ansi.println(t, Ansi.heading("选择渠道类型:"));
        for (int i = 0; i < options.size(); i++) {
            Ansi.println(t, String.format("  %d) %-10s %s", i + 1, options.get(i).id(), options.get(i).displayName()));
        }
        Ansi.println(t, Ansi.dim("  (stubs not offered: " + String.join(", ", stubIds()) + ")"));
        try {
            return options.get(Integer.parseInt(reader.readLine("Type number: ").trim()) - 1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A short "where to get the credentials" hint per channel type — so the user isn't guessing. */
    private void printSetupHint(Terminal t, ChannelType type) {
        String hint = switch (type) {
            case DINGTALK -> "钉钉群 → 设置 → 智能群助手 → 添加机器人 → 自定义 → 复制 Webhook；安全设置勾选「加签」复制密钥。";
            case FEISHU -> "飞书群 → 设置 → 群机器人 → 添加自定义机器人 → 复制 Webhook；勾选「签名校验」复制密钥（可选）。";
            case WEBHOOK -> "本地 HTTP webhook：设一个监听端口/路径，外部服务 POST 消息进来。";
            default -> null;
        };
        if (hint != null) {
            Ansi.println(t, Ansi.dim("  ↳ " + hint));
        }
    }

    /**
     * Read only the settings a channel actually needs; secrets are masked on input, never echoed. An
     * outbound robot (DingTalk/Feishu) needs just webhook-url + sign-secret — NO listen port/path (that
     * is only for the inbound HTTP {@code webhook} channel), so those cryptic prompts are gone. A pasted
     * webhook URL ({@code prefillUrl}) is reused so the user isn't asked to retype it.
     */
    private ChannelConfig readConfig(LineReader reader, ChannelType type, String prefillUrl) {
        ChannelConfig cc = new ChannelConfig();
        switch (type) {
            case DINGTALK -> {
                cc.setWebhookUrl(webhook(reader, prefillUrl));
                cc.setSignSecret(reader.readLine("加签密钥 sign-secret（可选，回车跳过）: ", '*').trim());
            }
            case FEISHU -> {
                cc.setWebhookUrl(webhook(reader, prefillUrl));
                cc.setSignSecret(reader.readLine("签名密钥 sign-secret（可选，回车跳过）: ", '*').trim());
                cc.setVerificationToken(reader.readLine("入站事件校验 token（可选，回车跳过）: ", '*').trim());
            }
            case WEBHOOK -> {
                cc.setToken(reader.readLine("Auth token (optional): ", '*').trim());
                readPortPath(reader, cc);
            }
            default -> { /* STDIN and any functional no-config channel need nothing. */ }
        }
        return cc;
    }

    /** Reuse a pasted webhook URL if we have one, else prompt for it. */
    private static String webhook(LineReader reader, String prefillUrl) {
        return (prefillUrl != null && !prefillUrl.isBlank())
                ? prefillUrl : reader.readLine("Outbound webhook-url: ").trim();
    }

    private void readPortPath(LineReader reader, ChannelConfig cc) {
        String port = reader.readLine("Listen port (blank = default): ").trim();
        if (port.matches("\\d+")) {
            cc.setPort(Integer.parseInt(port));
        }
        String path = reader.readLine("HTTP path (blank = default): ").trim();
        if (!path.isBlank()) {
            cc.setPath(path);
        }
    }

    /**
     * After add: for an outbound robot with a webhook-url, send one real test message and report — so
     * the user knows it works without a separate {@code /channel test}. Inbound channels bind on the
     * next launch (no listener started here). Best-effort: never throws, never echoes a credential.
     */
    private void autoTest(Terminal t, ChannelType type, ChannelConfig cc) {
        Channel channel;
        try {
            channel = type.create(cc);
        } catch (RuntimeException e) {
            return;
        }
        if (channel instanceof OutboundChannel out && isSet(cc.getWebhookUrl())) {
            Ansi.println(t, Ansi.dim("发送一条测试消息验证 " + type.id() + " …"));
            boolean ok;
            try {
                ok = out.send("", Notification.of(NotificationType.MESSAGE, Severity.NORMAL,
                        "PigAgent 连通性测试", "This is a channel connectivity test from PigAgent."));
            } catch (RuntimeException e) {
                ok = false;
            }
            Ansi.println(t, ok ? Ansi.success("✓ 测试消息已投递——机器人接受了消息。")
                    : Ansi.warn("测试消息未投递（检查 webhook-url / 加签密钥）。"));
        } else {
            Ansi.println(t, Ansi.dim("（入站渠道将在重启后监听；用 /channel test " + type.id() + " 验证。）"));
        }
    }

    private void removeChannel(Terminal t) {
        String id = resolveId(0);
        if (id == null) {
            Ansi.println(t, Ansi.warn("Usage: /channel remove <id|index>"));
            return;
        }
        if (!channels().containsKey(id)) {
            Ansi.println(t, Ansi.error("No such channel: " + id));
            return;
        }
        LineReader reader = ctx.readerRef().get();
        String answer = reader != null ? reader.readLine(Ansi.warn("Remove channel " + id + "? (y/N) ")) : "n";
        if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
            Ansi.println(t, Ansi.dim("Cancelled."));
            return;
        }
        ctx.configManager().updateConfig(c -> {
            Map<String, ChannelConfig> m = new LinkedHashMap<>(c.getChannels());
            m.remove(id);
            c.setChannels(m);
        });
        Ansi.println(t, Ansi.success("Removed " + id + " (restart to stop it if running)."));
    }

    private void toggleChannel(Terminal t, boolean enable) {
        String id = resolveId(0);
        if (id == null) {
            Ansi.println(t, Ansi.warn("Usage: /channel " + (enable ? "enable" : "disable") + " <id|index>"));
            return;
        }
        ChannelType type = ChannelType.fromId(id).orElse(null);
        if (enable && (type == null || !type.isFunctional())) {
            Ansi.println(t, Ansi.error("该渠道尚未实现（占位）— " + id
                    + " is a stub / unknown adapter and cannot be enabled."));
            return;
        }
        ChannelConfig existing = channels().get(id);
        if (existing == null) {
            Ansi.println(t, Ansi.error("No such channel: " + id + " (use /channel add first)."));
            return;
        }
        ctx.configManager().updateConfig(c -> {
            Map<String, ChannelConfig> m = new LinkedHashMap<>(c.getChannels());
            m.get(id).setEnabled(enable);
            c.setChannels(m);
        });
        Ansi.println(t, enable
                ? Ansi.success("Enabled " + id + ". ") + Ansi.dim("Connects on next launch — restart to activate.")
                : Ansi.warn("Disabled " + id + ". ") + Ansi.dim("Takes effect on next launch."));
    }

    private void testChannel(Terminal t) {
        String id = resolveId(0);
        if (id == null) {
            Ansi.println(t, Ansi.warn("Usage: /channel test <id|index>"));
            return;
        }
        ChannelType type = ChannelType.fromId(id).orElse(null);
        if (type == null) {
            Ansi.println(t, Ansi.error("Unknown channel adapter: " + id));
            return;
        }
        if (!type.isFunctional()) {
            Ansi.println(t, Ansi.warn("该渠道尚未实现（占位）— " + id + " is a stub, nothing to test."));
            return;
        }
        if (isRunning(id)) {
            Ansi.println(t, Ansi.success("Channel " + id + " is already running."));
            return;
        }
        if (type == ChannelType.STDIN) {
            Ansi.println(t, Ansi.info("stdin runs with the CLI process — no separate connectivity test."));
            return;
        }
        ChannelConfig cc = channels().getOrDefault(id, new ChannelConfig());
        Channel channel = type.create(cc);
        if (channel instanceof OutboundChannel out) {
            testOutbound(t, id, out, cc);
        } else {
            testInboundBind(t, id, channel);
        }
    }

    /** Robot channels (DingTalk/Feishu): dispatch a real test notification when a webhook-url is set. */
    private void testOutbound(Terminal t, String id, OutboundChannel out, ChannelConfig cc) {
        if (cc.getWebhookUrl() == null || cc.getWebhookUrl().isBlank()) {
            Ansi.println(t, Ansi.warn("No outbound webhook-url configured for " + id
                    + " — cannot send an outbound test."));
            return;
        }
        Ansi.println(t, Ansi.dim("Sending a test message via " + id + " ..."));
        boolean ok = out.send("", Notification.of(NotificationType.MESSAGE, Severity.NORMAL,
                "PigAgent 连通性测试", "This is a channel connectivity test from PigAgent."));
        Ansi.println(t, ok ? Ansi.success("Delivered — the robot accepted the message.")
                : Ansi.error("Delivery failed (check webhook-url / sign-secret)."));
    }

    /** Inbound HTTP channels (webhook/slack): bind the port briefly to verify it is listenable. */
    private void testInboundBind(Terminal t, String id, Channel channel) {
        Ansi.println(t, Ansi.dim("Trying to bind " + id + " ..."));
        try {
            channel.start(msg -> { });
            if (channel.isRunning()) {
                Ansi.println(t, Ansi.success("OK — " + id + " can listen on its configured port."));
            } else {
                Ansi.println(t, Ansi.error("Failed to bind " + id + " (port in use? check logs)."));
            }
        } finally {
            channel.stop();
        }
    }

    // --- helpers ------------------------------------------------------------

    private Map<String, ChannelConfig> channels() {
        Map<String, ChannelConfig> c = ctx.configManager().getConfig().getChannels();
        return c == null ? Map.of() : c;
    }

    /** Persist a channel config under {@code id} (overwriting any existing entry). */
    private void putChannel(String id, ChannelConfig cc) {
        ctx.configManager().updateConfig(c -> {
            Map<String, ChannelConfig> m = new LinkedHashMap<>(c.getChannels());
            m.put(id, cc);
            c.setChannels(m);
        });
    }

    private boolean isRunning(String id) {
        List<ChannelAgentBridge> bridges = ctx.bridges();
        if (bridges == null) {
            return false;
        }
        return bridges.stream().anyMatch(b -> id.equals(b.getChannel().channelId()) && b.getChannel().isRunning());
    }

    /** Resolve {@code args[i]} as a 1-based index into the configured list, or a channel id. */
    private String resolveId(int i) {
        if (args == null || args.length <= i) {
            return null;
        }
        String token = args[i];
        if (token.matches("\\d+")) {
            List<String> ids = new ArrayList<>(channels().keySet());
            int idx = Integer.parseInt(token) - 1;
            return (idx >= 0 && idx < ids.size()) ? ids.get(idx) : null;
        }
        return token; // a bare id: each action validates whether it is known/configured
    }

    /** A safe, credential-free label: only non-secret hints (never a token / URL / secret value). */
    private static String connectionHint(ChannelConfig cc) {
        List<String> parts = new ArrayList<>();
        if (cc.getPort() > 0) {
            parts.add("port " + cc.getPort());
        }
        if (cc.getPath() != null && !cc.getPath().isBlank()) {
            parts.add("path " + cc.getPath());
        }
        if (isSet(cc.getWebhookUrl())) {
            parts.add("webhook-url set");
        }
        if (isSet(cc.getToken())) {
            parts.add("token set");
        }
        if (isSet(cc.getSignSecret()) || isSet(cc.getSigningSecret())) {
            parts.add("secret set");
        }
        if (isSet(cc.getVerificationToken())) {
            parts.add("verify-token set");
        }
        return parts.isEmpty() ? "(no settings)" : String.join(" · ", parts);
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    private static List<ChannelType> functionalTypes() {
        List<ChannelType> out = new ArrayList<>();
        for (ChannelType type : ChannelType.values()) {
            if (type.isFunctional()) {
                out.add(type);
            }
        }
        return out;
    }

    private static List<String> functionalIds() {
        return functionalTypes().stream().map(ChannelType::id).toList();
    }

    private static List<String> stubIds() {
        List<String> out = new ArrayList<>();
        for (ChannelType type : ChannelType.values()) {
            if (!type.isFunctional()) {
                out.add(type.id());
            }
        }
        return out;
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/channel actions:"));
        Ansi.println(t, Ansi.dim("  list                    list configured channels + live status"));
        Ansi.println(t, Ansi.dim("  add                     add a channel（粘贴 webhook 自动识别 / 引导式，加完自动测试）"));
        Ansi.println(t, Ansi.dim("  remove <id|index>       remove a channel (asks to confirm)"));
        Ansi.println(t, Ansi.dim("  enable <id|index>       enable a channel (connects on next launch)"));
        Ansi.println(t, Ansi.dim("  disable <id|index>      disable a channel"));
        Ansi.println(t, Ansi.dim("  test <id|index>         test connectivity (dispatch or port bind)"));
    }
}
