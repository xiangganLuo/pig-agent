package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.agent.kernel.ToolActionResult;
import io.pigagent.core.agent.kernel.ToolAdmin;
import io.pigagent.core.agent.kernel.ToolGroupView;
import io.pigagent.core.agent.kernel.ToolInventoryEntry;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Optional;

/**
 * {@code /tools} — the operator "process table" for tools ({@code tools-observability}, T3), the
 * built-in-tool counterpart of {@code /mcp}. Read-only listing goes through the {@link AgentKernel}
 * façade ({@link AgentKernel#listTools()}); runtime management (groups / enable / disable / refresh)
 * goes through the façade's {@link ToolAdmin} seam. All output is credential-free (the inventory /
 * results are already sanitized upstream).
 */
@Command(name = "/tools",
        description = "Inspect and manage tools (list|info|enable|disable|groups|refresh)")
public final class ToolsCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "list | info | enable | disable | groups | refresh")
    String action;

    @Parameters(index = "1..*", paramLabel = "<args>")
    String[] args;

    public ToolsCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        AgentKernel kernel = ctx.agentKernel();
        String act = action == null ? "list" : action.toLowerCase();
        switch (act) {
            case "list" -> listTools(t, kernel);
            case "info" -> infoTool(t, kernel);
            case "enable" -> admin(t, kernel, "enable");
            case "disable" -> admin(t, kernel, "disable");
            case "groups" -> listGroups(t, kernel);
            case "refresh", "reeval" -> refresh(t, kernel);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private void listTools(Terminal t, AgentKernel kernel) {
        List<ToolInventoryEntry> tools = kernel.listTools();
        Ansi.println(t, Ansi.heading("Tools:"));
        if (tools.isEmpty()) {
            Ansi.println(t, Ansi.dim("  (none)"));
            return;
        }
        for (ToolInventoryEntry e : tools) {
            Ansi.println(t, "  " + Ansi.info(String.format("%-22s", e.name()))
                    + Ansi.dim(String.format("%-11s", e.risk())) + " " + availabilityLabel(e)
                    + deferralLabel(e) + metricsLabel(e));
        }
    }

    private void infoTool(Terminal t, AgentKernel kernel) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /tools info <name>"));
            return;
        }
        String name = args[0];
        Optional<ToolInventoryEntry> found = kernel.listTools().stream()
                .filter(e -> e.name().equals(name)).findFirst();
        if (found.isEmpty()) {
            Ansi.println(t, Ansi.error("No such tool: " + name));
            return;
        }
        ToolInventoryEntry e = found.get();
        Ansi.println(t, Ansi.heading("Tool: " + e.name()));
        Ansi.println(t, line("Risk", e.risk()));
        Ansi.println(t, line("Available", e.available() ? "yes"
                : "no — " + (e.availabilityReason().isBlank() ? "unavailable" : e.availabilityReason())));
        Ansi.println(t, line("Deferral", e.deferralStatus()));
        Ansi.println(t, line("Calls", String.valueOf(e.calls())));
        Ansi.println(t, line("Avg latency", e.avgLatencyMillis() + " ms"));
        Ansi.println(t, line("Error rate", String.format("%.0f%%", e.errorRate() * 100)));
    }

    private void listGroups(Terminal t, AgentKernel kernel) {
        Optional<ToolAdmin> admin = kernel.toolAdmin();
        if (admin.isEmpty()) {
            Ansi.println(t, Ansi.dim("Tool management is unavailable."));
            return;
        }
        List<ToolGroupView> groups = admin.get().groups();
        Ansi.println(t, Ansi.heading("Tool groups:"));
        if (groups.isEmpty()) {
            Ansi.println(t, Ansi.dim("  (none)"));
            return;
        }
        for (ToolGroupView g : groups) {
            String state = g.active() ? Ansi.success("active") : Ansi.warn("inactive");
            Ansi.println(t, "  " + Ansi.info(String.format("%-24s", g.name())) + state
                    + Ansi.dim("  " + g.tools()));
        }
    }

    private void admin(Terminal t, AgentKernel kernel, String verb) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /tools " + verb + " <name>"));
            return;
        }
        Optional<ToolAdmin> admin = kernel.toolAdmin();
        if (admin.isEmpty()) {
            Ansi.println(t, Ansi.dim("Tool management is unavailable."));
            return;
        }
        ToolActionResult r = verb.equals("enable")
                ? admin.get().enable(args[0]) : admin.get().disable(args[0]);
        Ansi.println(t, r.changed() ? Ansi.success(r.message()) : Ansi.dim(r.message()));
    }

    private void refresh(Terminal t, AgentKernel kernel) {
        Optional<ToolAdmin> admin = kernel.toolAdmin();
        if (admin.isEmpty()) {
            Ansi.println(t, Ansi.dim("Tool management is unavailable."));
            return;
        }
        ToolActionResult r = admin.get().refresh();
        Ansi.println(t, r.changed() ? Ansi.success(r.message()) : Ansi.dim(r.message()));
    }

    private static String availabilityLabel(ToolInventoryEntry e) {
        return e.available() ? Ansi.success("available")
                : Ansi.error("unavailable")
                + Ansi.dim(e.availabilityReason().isBlank() ? "" : " (" + e.availabilityReason() + ")");
    }

    private static String deferralLabel(ToolInventoryEntry e) {
        if (ToolInventoryEntry.DEFERRAL_DEFERRED.equals(e.deferralStatus())) {
            return Ansi.warn("  [deferred]");
        }
        if (ToolInventoryEntry.DEFERRAL_REVEALED.equals(e.deferralStatus())) {
            return Ansi.dim("  [revealed]");
        }
        return "";
    }

    private static String metricsLabel(ToolInventoryEntry e) {
        if (e.calls() <= 0) {
            return "";
        }
        return Ansi.dim(String.format("  · %d call(s), %dms avg, %.0f%% err",
                e.calls(), e.avgLatencyMillis(), e.errorRate() * 100));
    }

    private static String line(String label, String value) {
        return "  " + Ansi.bold(String.format("%-14s", label), org.fusesource.jansi.Ansi.Color.CYAN)
                + Ansi.info(value);
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/tools actions:"));
        Ansi.println(t, Ansi.dim("  list                    list tools with risk / availability / deferral / metrics"));
        Ansi.println(t, Ansi.dim("  info <name>             show one tool's details + metrics"));
        Ansi.println(t, Ansi.dim("  enable <name>           reveal a deferred tool (make it visible)"));
        Ansi.println(t, Ansi.dim("  disable <name>          defer a tool (hide it from the model)"));
        Ansi.println(t, Ansi.dim("  groups                  list tool groups (capability packs) + active state"));
        Ansi.println(t, Ansi.dim("  refresh                 re-evaluate tool availability (no restart)"));
    }
}
