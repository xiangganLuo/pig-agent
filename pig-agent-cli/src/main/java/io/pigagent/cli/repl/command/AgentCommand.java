package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.agent.kernel.ExposedSubagent;
import io.pigagent.model.StoredModel;
import io.pigagent.task.TaskScheduler;
import org.fusesource.jansi.Ansi.Color;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /agent} — manage agents through the {@link AgentKernel} façade: list them, switch the
 * active one, create/update, and (for digital employees) run a mandate now / view reports. The CLI
 * is just an adapter over the kernel; it does not touch the internal registry/repository/factory.
 */
@Command(name = "/agent", description = "Manage agents (list|use|new|model|run|report|sub)")
public final class AgentCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "list | use | new | model | run | report | sub")
    String action;

    @Parameters(index = "1..*", paramLabel = "<args>")
    String[] args;

    @Option(names = "--mandate", paramLabel = "<text>",
            description = "with 'new': the mandate the agent runs unattended (makes it a digital employee)")
    String mandate;

    @Option(names = "--schedule", paramLabel = "<cron>",
            description = "with 'new': a 5-field cron (e.g. \"0 2 * * *\"); requires --mandate")
    String schedule;

    @Option(names = "--allow", paramLabel = "<cmd1,cmd2>",
            description = "with 'new': comma-separated command allowlist for unattended runs")
    String allow;

    public AgentCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        String act = action == null ? "list" : action.toLowerCase();
        switch (act) {
            case "list" -> list(t);
            case "use", "switch" -> use(t);
            case "new" -> create(t);
            case "model" -> setModel(t);
            case "run" -> runNow(t);
            case "report" -> report(t);
            case "sub" -> sub(t);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private void list(Terminal t) {
        AgentKernel kernel = ctx.agentKernel();
        String active = kernel.activeId();
        Ansi.println(t, Ansi.heading("Agents:"));
        int i = 1;
        for (AgentInstance inst : kernel.listAgents()) {
            AgentSpec s = inst.spec();
            String marker = inst.id().equals(active) ? Ansi.bold(" *", Color.GREEN) : "  ";
            String model = s.modelId() == null ? "(default)" : s.modelId();
            String tools = s.usesAllTools() ? "all" : String.valueOf(s.toolNames().size());
            String auto = s.isAutonomous() ? "  ⏰" + s.schedule() : "";
            Ansi.println(t, String.format("  %2d)", i) + marker + " " + Ansi.info(s.name())
                    + Ansi.dim(" [" + inst.id() + "]  model=" + model + "  tools=" + tools + auto));
            i++;
        }
    }

    private void use(Terminal t) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /agent use <id>"));
            return;
        }
        String id = args[0];
        if (ctx.agentKernel().useAgent(id)) {
            Ansi.println(t, Ansi.success("Switched to agent: ") + Ansi.info(id));
        } else {
            Ansi.println(t, Ansi.error("No such agent: " + id));
        }
    }

    private void create(Terminal t) {
        if (args == null || args.length < 2) {
            Ansi.println(t, Ansi.warn("Usage: /agent new <id> <name...> [modelId]"
                    + " [--mandate \"<text>\"] [--schedule \"<cron>\"] [--allow \"<cmd1,cmd2>\"]"));
            return;
        }
        String id = args[0];
        if (ctx.agentKernel().getAgent(id).isPresent()) {
            Ansi.println(t, Ansi.error("Agent id already exists: " + id));
            return;
        }
        // The trailing token is treated as a modelId ONLY when it resolves to a saved model; otherwise
        // it is part of a multi-word (unquoted) name — so "/agent new bot My Bot" keeps the full name
        // instead of dropping "Bot", and a bogus trailing model is never silently applied.
        String modelId = null;
        int nameEnd = args.length;
        if (args.length >= 3 && isValidModel(args[args.length - 1])) {
            modelId = args[args.length - 1];
            nameEnd = args.length - 1;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, nameEnd)).strip();
        if (name.isBlank()) {
            Ansi.println(t, Ansi.warn("Usage: /agent new <id> <name...> [modelId]"));
            return;
        }

        boolean hasMandate = mandate != null && !mandate.isBlank();
        boolean hasSchedule = schedule != null && !schedule.isBlank();
        if (hasSchedule && !TaskScheduler.isValidCron(schedule.trim())) {
            Ansi.println(t, Ansi.error("Invalid cron schedule: \"" + schedule.trim()
                    + "\" — expected a 5-field cron, e.g. \"0 2 * * *\"."));
            return;
        }
        if (hasSchedule && !hasMandate) {
            Ansi.println(t, Ansi.error(
                    "A scheduled agent needs work to do; add --mandate \"<what it should do>\"."));
            return;
        }

        AgentSpec spec = AgentSpec.create(id, name).withModelId(modelId);
        if (hasMandate) {
            spec = spec.withMandate(mandate.trim());
        }
        if (hasSchedule) {
            spec = spec.withSchedule(schedule.trim());
        }
        if (allow != null && !allow.isBlank()) {
            List<String> cmds = Arrays.stream(allow.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            spec = spec.withCommandAllowlist(cmds);
        }
        try {
            ctx.agentKernel().createAgent(spec);
            String kind = spec.isAutonomous() ? "  (digital employee ⏰" + spec.schedule() + ")" : "";
            Ansi.println(t, Ansi.success("Created agent: ")
                    + Ansi.info(name + " [" + id + "]"
                    + (modelId == null ? "" : " model=" + modelId) + kind));
            if (spec.isAutonomous()) {
                Ansi.println(t, Ansi.dim("Scheduled runs begin on next launch; use '/agent run "
                        + id + "' to run it now."));
            }
        } catch (Exception e) {
            Ansi.println(t, Ansi.error("Failed to create agent: " + e.getMessage()));
        }
    }

    private void setModel(Terminal t) {
        if (args == null || args.length < 2) {
            Ansi.println(t, Ansi.warn("Usage: /agent model <id> <modelId>"));
            return;
        }
        String id = args[0];
        String modelId = args[1];
        AgentInstance inst = ctx.agentKernel().getAgent(id).orElse(null);
        if (inst == null) {
            Ansi.println(t, Ansi.error("No such agent: " + id));
            return;
        }
        // Validate against the saved models first: an unsaved id would otherwise silently fall back to
        // the default at build time while this printed a false "now uses model: <bogus>".
        if (!isValidModel(modelId)) {
            Ansi.println(t, Ansi.error("No such model: " + modelId + validModelsHint()));
            return;
        }
        try {
            ctx.agentKernel().updateAgent(inst.spec().withModelId(modelId));
            Ansi.println(t, Ansi.success("Agent " + id + " now uses model: ") + Ansi.info(modelId));
        } catch (Exception e) {
            Ansi.println(t, Ansi.error("Failed to switch model: " + e.getMessage()));
        }
    }

    /** True iff {@code modelId} names a saved model (mirrors how {@code /model} validates). */
    private boolean isValidModel(String modelId) {
        return modelId != null && ctx.modelManager() != null
                && ctx.modelManager().findById(modelId).isPresent();
    }

    /** A friendly ", valid: [...]" suffix listing the saved model ids (never a credential). */
    private String validModelsHint() {
        if (ctx.modelManager() == null) {
            return "";
        }
        List<String> ids = ctx.modelManager().list().stream().map(StoredModel::id).toList();
        return ids.isEmpty() ? " (no models configured — use /model add)" : " — valid: " + ids;
    }

    /** Manually trigger one autonomous run of an agent's mandate now. */
    private void runNow(Terminal t) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /agent run <id>"));
            return;
        }
        String id = args[0];
        AgentInstance inst = ctx.agentKernel().getAgent(id).orElse(null);
        if (inst == null) {
            Ansi.println(t, Ansi.error("No such agent: " + id));
            return;
        }
        if (inst.spec().mandate() == null || inst.spec().mandate().isBlank()) {
            Ansi.println(t, Ansi.warn("Agent '" + id + "' has no mandate; nothing to run."));
            return;
        }
        Ansi.println(t, Ansi.dim("Running '" + id + "' …"));
        ctx.agentKernel().runNow(id).ifPresentOrElse(
                r -> Ansi.println(t, Ansi.success("Done (" + r.outcome() + "). ")
                        + Ansi.dim("See /agent report.")),
                () -> Ansi.println(t, Ansi.warn("Skipped: a run is already in progress.")));
    }

    /**
     * {@code /agent sub list|view <id>|switch <id>|back} — view + switch into an exposed subagent
     * (subagent-online-switch). Only subagents the active agent exposed via {@code
     * agent_spawn(expose_to_user=true)} are switchable; non-exposed background spawns are view-only via
     * their {@code task_output}. Switch state is the shared {@link io.pigagent.cli.repl.SubagentSwitchState}
     * the REPL run loop reads to route input.
     */
    private void sub(Terminal t) {
        String subAction = (args == null || args.length == 0) ? "list" : args[0].toLowerCase();
        switch (subAction) {
            case "list" -> subList(t);
            case "view" -> subView(t);
            case "switch", "use" -> subSwitch(t);
            case "back", "parent", "exit" -> subBack(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown sub action: " + subAction));
                Ansi.println(t, Ansi.dim("Usage: /agent sub list | view <id> | switch <id> | back"));
            }
        }
    }

    private void subList(Terminal t) {
        List<ExposedSubagent> subs = ctx.agentKernel().listSubagents();
        String current = ctx.subagentSwitch().current();
        if (subs.isEmpty()) {
            Ansi.println(t, Ansi.dim("No exposed subagents. A subagent becomes switchable when the "
                    + "agent spawns it with expose_to_user=true."));
            return;
        }
        Ansi.println(t, Ansi.heading("Exposed subagents:"));
        int i = 1;
        for (ExposedSubagent s : subs) {
            String marker = s.id().equals(current) ? Ansi.bold(" *", Color.GREEN) : "  ";
            Ansi.println(t, String.format("  %2d)", i) + marker + " " + Ansi.info(s.display())
                    + Ansi.dim(" [" + s.id() + "]  type=" + s.agentId()));
            i++;
        }
        if (current != null) {
            Ansi.println(t, Ansi.dim("Currently switched into [" + current + "]. /agent sub back to return."));
        }
    }

    private void subView(Terminal t) {
        if (args == null || args.length < 2) {
            Ansi.println(t, Ansi.warn("Usage: /agent sub view <id>"));
            return;
        }
        String id = args[1];
        ctx.agentKernel().subagentOutput(id).ifPresentOrElse(
                s -> {
                    Ansi.println(t, Ansi.heading("Subagent " + s.display()));
                    Ansi.println(t, Ansi.dim("  id:   " + s.id()));
                    Ansi.println(t, Ansi.dim("  type: " + s.agentId()));
                    Ansi.println(t, Ansi.dim("  switch into it with: /agent sub switch " + s.id()));
                },
                () -> Ansi.println(t, Ansi.error("No such exposed subagent: " + id)));
    }

    private void subSwitch(Terminal t) {
        if (args == null || args.length < 2) {
            Ansi.println(t, Ansi.warn("Usage: /agent sub switch <id>"));
            return;
        }
        String id = args[1];
        if (ctx.agentKernel().subagentOutput(id).isEmpty()) {
            Ansi.println(t, Ansi.error("No such exposed subagent: " + id
                    + " — use /agent sub list to see switchable subagents."));
            return;
        }
        ctx.subagentSwitch().switchTo(id);
        Ansi.println(t, Ansi.success("Switched into subagent ") + Ansi.info(id)
                + Ansi.dim(" — your messages now go to it. /agent sub back to return."));
    }

    private void subBack(Terminal t) {
        if (!ctx.subagentSwitch().isActive()) {
            Ansi.println(t, Ansi.dim("Not currently switched into a subagent."));
            return;
        }
        ctx.subagentSwitch().back();
        Ansi.println(t, Ansi.success("Returned to the main agent."));
    }

    /** List recent morning reports. */
    private void report(Terminal t) {
        java.nio.file.Path dir = ctx.reportsDir();
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            Ansi.println(t, Ansi.dim("No reports yet."));
            return;
        }
        Ansi.println(t, Ansi.heading("Reports (" + dir + "):"));
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir, 2)) {
            java.util.List<java.nio.file.Path> files = walk
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted(java.util.Comparator.reverseOrder())
                    .limit(20).toList();
            if (files.isEmpty()) {
                Ansi.println(t, Ansi.dim("  (none)"));
            }
            for (java.nio.file.Path p : files) {
                Ansi.println(t, "  " + Ansi.info(dir.relativize(p).toString()));
            }
        } catch (java.io.IOException e) {
            Ansi.println(t, Ansi.error("Failed to list reports: " + e.getMessage()));
        }
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/agent actions:"));
        Ansi.println(t, Ansi.dim("  list                       list agents (* = active, ⏰ = scheduled)"));
        Ansi.println(t, Ansi.dim("  use <id>                   switch the active agent"));
        Ansi.println(t, Ansi.dim("  new <id> <name...> [model] create an agent (name may be multi-word; "
                + "a trailing saved model id is used as its model)"));
        Ansi.println(t, Ansi.dim("      [--mandate \"<text>\"]    autonomous mandate → makes it a digital employee"));
        Ansi.println(t, Ansi.dim("      [--schedule \"<cron>\"]   5-field cron, e.g. \"0 2 * * *\" (needs --mandate)"));
        Ansi.println(t, Ansi.dim("      [--allow \"<c1,c2>\"]     command allowlist for unattended runs"));
        Ansi.println(t, Ansi.dim("  model <id> <modelId>       change an agent's model (must be a saved id)"));
        Ansi.println(t, Ansi.dim("  run <id>                   run a digital-employee mandate now"));
        Ansi.println(t, Ansi.dim("  report                     list recent morning reports"));
        Ansi.println(t, Ansi.dim("  sub list|view <id>|switch <id>|back"));
        Ansi.println(t, Ansi.dim("                             view + switch into an exposed subagent"));
    }
}
