package io.pigagent.core.agent;

/**
 * Immutable value object carrying pig's native Plan Mode configuration down to the
 * {@link PigAgent.Builder} (av2). It maps 1:1 to the three {@code HarnessAgent.Builder} plan
 * knobs — {@code enablePlanMode()} / {@code planFileDirectory(String)} /
 * {@code allowShellInPlanMode(boolean)} — so the wiring layer ({@code AgentBootstrap}) can turn
 * config into a single, default-safe carrier instead of threading three loose primitives through
 * every factory constructor.
 *
 * <p><b>Read-only default.</b> {@link #disabled()} is the zero-behavior-change baseline (no plan
 * tools, no {@code PlanModeMiddleware}); {@code allowShell} defaults {@code false} so the plan
 * phase stays read-only. A blank {@code planDir} normalizes to the native default {@code "plans"}
 * (workspace-relative), so the object is always valid to pass on to the builder.
 */
public record PlanModeSettings(boolean enabled, String planDir, boolean allowShell) {

    /** The native default plan-file directory (workspace-relative), mirroring the harness default. */
    public static final String DEFAULT_PLAN_DIR = "plans";

    public PlanModeSettings {
        if (planDir == null || planDir.isBlank()) {
            planDir = DEFAULT_PLAN_DIR;
        }
    }

    /** The disabled baseline: no plan tools, read-only default dir — exactly today's behavior. */
    public static PlanModeSettings disabled() {
        return new PlanModeSettings(false, DEFAULT_PLAN_DIR, false);
    }
}
