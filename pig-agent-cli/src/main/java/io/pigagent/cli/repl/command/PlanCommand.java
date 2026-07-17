package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.config.PigAgentConfig.PlanModeConfig;
import io.pigagent.core.agent.PigAgent;
import org.fusesource.jansi.Ansi.Color;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code /plan} — drive native AgentScope Plan Mode for the active session (av2).
 *
 * <p>Plan Mode is the structured "think before acting" flow: while active the agent is in a
 * read-only plan phase (the native {@code PlanModeMiddleware} denies every non-read-only tool),
 * the model writes a {@code PLAN.md} via {@code plan_write}, and leaving the phase is human-gated —
 * the model's {@code plan_exit} tool triggers a HITL confirmation (handled by the REPL's existing
 * confirm loop) before execution begins.
 *
 * <p>This command drives the programmatic entry points on the active agent
 * ({@link PigAgent#enterPlanMode(String)} / {@link PigAgent#exitPlanMode(String)} /
 * {@link PigAgent#isPlanModeActive(String)}). It follows {@code /permission}'s pattern of acting on
 * the active agent directly (via the holder) for an immediate runtime effect. {@code /plan enter} is
 * gated on the {@code plan-mode.enabled} config: with Plan Mode disabled the vehicle has no plan
 * tools + no read-only enforcer, so entering would be an unenforced flag flip — the command refuses
 * and points the user at the config.
 *
 * <p><b>Relationship to {@code /permission mode plan} (EXPLORE):</b> orthogonal read-only mechanisms.
 * EXPLORE is a permission-engine toggle; native Plan Mode is the structured plan+HITL flow. Entering/
 * exiting Plan Mode does not change the permission mode, and Plan Mode's read-only holds regardless
 * of the permission mode.
 */
@Command(name = "/plan", description = "Native Plan Mode for the active session (enter|exit|status)")
public final class PlanCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>",
            description = "enter | exit | status")
    String action;

    public PlanCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        String act = action == null ? "status" : action.toLowerCase();
        switch (act) {
            case "status" -> showStatus(t);
            case "enter" -> enter(t);
            case "exit" -> exit(t);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    private PlanModeConfig planCfg() {
        return ctx.configManager().getConfig().getPlanMode();
    }

    private String sessionId() {
        return ctx.sessionManager() == null ? null : ctx.sessionManager().getCurrentSessionId();
    }

    private void showStatus(Terminal t) {
        PlanModeConfig cfg = planCfg();
        Ansi.println(t, Ansi.heading("Plan Mode"));
        Ansi.println(t, line("Enabled", cfg.isEnabled() ? "on" : "off (set plan-mode.enabled)"));
        boolean active = cfg.isEnabled() && safeActive();
        Ansi.println(t, line("Active", active ? Ansi.warn("yes — read-only plan phase") : "no"));
        Ansi.println(t, line("Plan dir", cfg.getPlanDir() + "/PLAN.md"));
        Ansi.println(t, line("Allow shell", cfg.isAllowShell() ? "yes (native execute only)" : "no"));
    }

    private void enter(Terminal t) {
        if (!planCfg().isEnabled()) {
            Ansi.println(t, Ansi.error("Plan Mode is disabled — set plan-mode.enabled: true in "
                    + "application.yaml and restart to make the plan tools available."));
            return;
        }
        PigAgent agent = ctx.agent();
        if (agent == null) {
            Ansi.println(t, Ansi.error("No active agent."));
            return;
        }
        try {
            agent.enterPlanMode(sessionId());
        } catch (Exception e) {
            Ansi.println(t, Ansi.error("Could not enter Plan Mode: " + e.getMessage()));
            return;
        }
        Ansi.println(t, Ansi.success("Entered Plan Mode — read-only. ")
                + Ansi.dim("Describe the goal; the agent plans + writes PLAN.md, then asks to exit."));
    }

    private void exit(Terminal t) {
        PigAgent agent = ctx.agent();
        if (agent == null) {
            Ansi.println(t, Ansi.error("No active agent."));
            return;
        }
        try {
            agent.exitPlanMode(sessionId());
        } catch (Exception e) {
            Ansi.println(t, Ansi.error("Could not exit Plan Mode: " + e.getMessage()));
            return;
        }
        Ansi.println(t, Ansi.success("Exited Plan Mode. ")
                + Ansi.dim("Execution is enabled again (subject to the active permission mode)."));
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/plan actions:"));
        Ansi.println(t, Ansi.dim("  status   show whether Plan Mode is enabled + active + the plan file"));
        Ansi.println(t, Ansi.dim("  enter    enter the read-only plan phase for this session"));
        Ansi.println(t, Ansi.dim("  exit     leave the plan phase (you are the approver; no extra prompt)"));
        Ansi.println(t, Ansi.dim("  note: the model may self-enter via plan_enter; plan_exit is HITL-gated"));
    }

    private boolean safeActive() {
        try {
            PigAgent agent = ctx.agent();
            return agent != null && agent.isPlanModeActive(sessionId());
        } catch (Exception e) {
            return false;
        }
    }

    private static String line(String label, String value) {
        return "  " + Ansi.bold(String.format("%-12s", label), Color.CYAN) + Ansi.info(value);
    }
}
