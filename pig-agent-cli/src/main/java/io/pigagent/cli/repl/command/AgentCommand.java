package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import org.fusesource.jansi.Ansi.Color;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code /agent} — manage the multiple agents held by the {@link AgentRegistry}: list them,
 * switch the active one, create a new one, or change an agent's model. Each agent has its own
 * model / tool subset / permission mode via its {@link AgentSpec}; the "default" agent is the
 * single-agent equivalent and stays active until switched.
 */
@Command(name = "/agent", description = "Manage agents (list|use|new|model)")
public final class AgentCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "list | use | new | model | run | report")
    String action;

    @Parameters(index = "1..*", paramLabel = "<args>")
    String[] args;

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
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    /** Manually trigger one autonomous run of an agent's mandate now. */
    private void runNow(Terminal t) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /agent run <id>"));
            return;
        }
        String id = args[0];
        io.pigagent.core.agent.AgentSpec spec = ctx.agentRepository().findById(id).orElse(null);
        if (spec == null) {
            Ansi.println(t, Ansi.error("No such agent (needs a saved spec): " + id));
            return;
        }
        if (spec.mandate() == null || spec.mandate().isBlank()) {
            Ansi.println(t, Ansi.warn("Agent '" + id + "' has no mandate; nothing to run."));
            return;
        }
        Ansi.println(t, Ansi.dim("Running '" + id + "' …"));
        ctx.agentRunner().run(spec).ifPresentOrElse(
                r -> Ansi.println(t, Ansi.success("Done (" + r.outcome() + "). ")
                        + Ansi.dim("See /agent report.")),
                () -> Ansi.println(t, Ansi.warn("Skipped: a run is already in progress.")));
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

    private void list(Terminal t) {
        AgentRegistry reg = ctx.agentRegistry();
        String active = reg.activeId();
        Ansi.println(t, Ansi.heading("Agents:"));
        int i = 1;
        for (AgentInstance inst : reg.list()) {
            AgentSpec s = inst.spec();
            String marker = inst.id().equals(active) ? Ansi.bold(" *", Color.GREEN) : "  ";
            String model = s.modelId() == null ? "(default)" : s.modelId();
            String tools = s.usesAllTools() ? "all" : String.valueOf(s.toolNames().size());
            Ansi.println(t, String.format("  %2d)", i) + marker + " " + Ansi.info(s.name())
                    + Ansi.dim(" [" + inst.id() + "]  model=" + model + "  tools=" + tools));
            i++;
        }
    }

    private void use(Terminal t) {
        if (args == null || args.length == 0) {
            Ansi.println(t, Ansi.warn("Usage: /agent use <id>"));
            return;
        }
        String id = args[0];
        if (ctx.agentRegistry().setActive(id)) {
            Ansi.println(t, Ansi.success("Switched to agent: ") + Ansi.info(id));
        } else {
            Ansi.println(t, Ansi.error("No such agent: " + id));
        }
    }

    private void create(Terminal t) {
        if (args == null || args.length < 2) {
            Ansi.println(t, Ansi.warn("Usage: /agent new <id> <name> [modelId]"));
            return;
        }
        String id = args[0];
        if (ctx.agentRegistry().contains(id)) {
            Ansi.println(t, Ansi.error("Agent id already exists: " + id));
            return;
        }
        String name = args[1];
        String modelId = args.length >= 3 ? args[2] : null;
        AgentSpec spec = AgentSpec.create(id, name).withModelId(modelId);
        try {
            AgentInstance inst = ctx.instanceFactory().create(spec);
            ctx.agentRepository().save(spec);
            ctx.agentRegistry().register(inst);
            Ansi.println(t, Ansi.success("Created agent: ")
                    + Ansi.info(name + " [" + id + "]" + (modelId == null ? "" : " model=" + modelId)));
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
        AgentInstance inst = ctx.agentRegistry().get(id).orElse(null);
        if (inst == null) {
            Ansi.println(t, Ansi.error("No such agent: " + id));
            return;
        }
        AgentSpec updated = inst.spec().withModelId(modelId);
        try {
            AgentInstance rebuilt = ctx.instanceFactory().create(updated);
            ctx.agentRepository().save(updated);
            ctx.agentRegistry().register(rebuilt);
            if (id.equals(ctx.agentRegistry().activeId())) {
                ctx.agentRegistry().setActive(id); // re-point the holder at the rebuilt agent
            }
            Ansi.println(t, Ansi.success("Agent " + id + " now uses model: ") + Ansi.info(modelId));
        } catch (Exception e) {
            Ansi.println(t, Ansi.error("Failed to switch model: " + e.getMessage()));
        }
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/agent actions:"));
        Ansi.println(t, Ansi.dim("  list                       list agents (* = active)"));
        Ansi.println(t, Ansi.dim("  use <id>                   switch the active agent"));
        Ansi.println(t, Ansi.dim("  new <id> <name> [modelId]  create + register a new agent"));
        Ansi.println(t, Ansi.dim("  model <id> <modelId>       change an agent's model"));
        Ansi.println(t, Ansi.dim("  run <id>                   run a digital-employee mandate now"));
        Ansi.println(t, Ansi.dim("  report                     list recent morning reports"));
    }
}
