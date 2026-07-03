package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.config.PermissionMode;
import io.pigagent.config.PigAgentConfig.PermissionConfig;
import org.fusesource.jansi.Ansi.Color;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

/**
 * {@code /permission} — 运行时管理工具权限（模式 + allowlist）。
 *
 * <p>模式 plan/ask/auto/bypass 由 {@link io.pigagent.tool.permission.ToolPermissionHook} 在
 * {@code PreActingEvent} 强制执行。这里只读写 {@code permissions} 配置块并即时持久化。
 */
@Command(name = "/permission",
        description = "Manage tool permissions (status|mode|channel-mode|allow|revoke|reset|list)")
public final class PermissionCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "status | mode | channel-mode | allow | revoke | reset | list")
    String action;

    @Parameters(index = "1..*", paramLabel = "<args>")
    String[] args;

    @Option(names = "--tool", description = "with 'allow': treat the name as a tool (default: a command key)")
    boolean asTool;

    public PermissionCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        String act = action == null ? "status" : action.toLowerCase();
        switch (act) {
            case "status" -> showStatus(t);
            case "list" -> listAll(t);
            case "mode" -> setMode(t, false);
            case "channel-mode" -> setMode(t, true);
            case "allow" -> allow(t);
            case "revoke" -> revoke(t);
            case "reset" -> reset(t);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private PermissionConfig perms() {
        return ctx.configManager().getConfig().getPermissions();
    }

    private void showStatus(Terminal t) {
        PermissionConfig p = perms();
        Ansi.println(t, Ansi.heading("Permissions"));
        Ansi.println(t, line("Mode", p.resolveMode().name().toLowerCase()));
        Ansi.println(t, line("Channel", p.resolveChannelMode().name().toLowerCase()));
        Ansi.println(t, line("Allow tools", String.valueOf(p.getAllowlist().getTools().size())));
        Ansi.println(t, line("Allow cmds", String.valueOf(p.getAllowlist().getCommands().size())));
    }

    private void listAll(Terminal t) {
        PermissionConfig p = perms();
        showStatus(t);
        Ansi.println(t, Ansi.dim("  modes: plan(只读否决可变工具) ask(逐次确认) "
                + "auto(放行编辑/网络,拦执行) bypass(全放行)"));
        Ansi.println(t, Ansi.dim("  allow-tools: " + p.getAllowlist().getTools()));
        Ansi.println(t, Ansi.dim("  allow-cmds:  " + p.getAllowlist().getCommands()));
    }

    private void setMode(Terminal t, boolean channel) {
        String label = channel ? "channel-mode" : "mode";
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /permission " + label + " <plan|ask|auto|bypass>"));
            return;
        }
        PermissionMode m = PermissionMode.fromString(args[0], null);
        if (m == null) {
            Ansi.println(t, Ansi.error("Invalid mode: " + args[0] + " (plan|ask|auto|bypass)"));
            return;
        }
        String v = m.name().toLowerCase();
        ctx.configManager().updateConfig(cfg -> {
            if (channel) {
                cfg.getPermissions().setChannelMode(v);
            } else {
                cfg.getPermissions().setMode(v);
            }
        });
        Ansi.println(t, Ansi.success((channel ? "Channel mode" : "Mode") + " set to ") + Ansi.info(v));
    }

    private void allow(Terminal t) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /permission allow <command-key>  (or: allow --tool <toolName>)"));
            return;
        }
        String name = args[0];
        ctx.configManager().updateConfig(cfg -> {
            List<String> list = asTool
                    ? cfg.getPermissions().getAllowlist().getTools()
                    : cfg.getPermissions().getAllowlist().getCommands();
            if (!list.contains(name)) {
                list.add(name);
            }
        });
        Ansi.println(t, Ansi.success("Allowlisted " + (asTool ? "tool" : "command") + ": ") + Ansi.info(name));
    }

    private void revoke(Terminal t) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /permission revoke <tool|command>"));
            return;
        }
        String name = args[0];
        ctx.configManager().updateConfig(cfg -> {
            cfg.getPermissions().getAllowlist().getTools().remove(name);
            cfg.getPermissions().getAllowlist().getCommands().remove(name);
        });
        Ansi.println(t, Ansi.success("Revoked: ") + Ansi.info(name));
    }

    private void reset(Terminal t) {
        ctx.configManager().updateConfig(cfg -> {
            cfg.getPermissions().getAllowlist().getTools().clear();
            cfg.getPermissions().getAllowlist().getCommands().clear();
        });
        Ansi.println(t, Ansi.success("Allowlist cleared."));
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/permission actions:"));
        Ansi.println(t, Ansi.dim("  status                        show mode + allowlist summary"));
        Ansi.println(t, Ansi.dim("  mode <plan|ask|auto|bypass>   set the tool permission mode"));
        Ansi.println(t, Ansi.dim("  channel-mode <mode>           set the non-interactive channel mode"));
        Ansi.println(t, Ansi.dim("  allow <cmd> | allow --tool <name>   allowlist a command key or tool"));
        Ansi.println(t, Ansi.dim("  revoke <name>                 remove from allowlist"));
        Ansi.println(t, Ansi.dim("  reset                         clear the allowlist"));
        Ansi.println(t, Ansi.dim("  list                          show modes + full allowlist"));
    }

    private static String line(String label, String value) {
        return "  " + Ansi.bold(String.format("%-12s", label), Color.CYAN) + Ansi.info(value);
    }
}
