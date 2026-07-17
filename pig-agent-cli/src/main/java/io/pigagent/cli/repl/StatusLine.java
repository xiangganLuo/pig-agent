package io.pigagent.cli.repl;

import io.pigagent.cli.Ansi;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.session.Session;
import io.pigagent.session.SessionManager;

/**
 * One-line status shown above the prompt: {@code model · session · perms}.
 *
 * <p>Pure assembly + a manager-reading convenience. Only non-sensitive fields are surfaced —
 * the model is shown via {@link StoredModel#label()} (protocol / model name), never the API key.
 * Kept simple and reliable: printed above each prompt rather than pinned to the terminal bottom.
 */
public final class StatusLine {

    private static final String SEP = "  ·  ";
    private static final String NONE = "(none)";
    private static final String DEFAULT_MODE = "ask";
    /** Distinct banner appended when native Plan Mode is active for the current session. */
    private static final String PLAN_BADGE = "⏸ PLAN";

    private StatusLine() {
    }

    /** Assemble the (dimmed) status line from already-extracted, non-sensitive values. */
    public static String format(String model, String session, String permMode) {
        return format(model, session, permMode, false);
    }

    /**
     * Assemble the status line, appending a distinct {@code ⏸ PLAN} badge when native Plan Mode is
     * active — the always-visible indicator that the agent is in the read-only plan phase.
     */
    public static String format(String model, String session, String permMode, boolean planActive) {
        String m = blankTo(model, NONE);
        String s = blankTo(session, NONE);
        String p = blankTo(permMode, DEFAULT_MODE);
        String base = Ansi.dim(m + SEP + s + SEP + p);
        return planActive ? base + Ansi.dim(SEP) + Ansi.warn(PLAN_BADGE) : base;
    }

    /** Read the current model label, session name and permission mode from the shared managers. */
    public static String from(ModelManager models, SessionManager sessions, ConfigurationManager config) {
        return from(models, sessions, config, false);
    }

    /** As {@link #from(ModelManager, SessionManager, ConfigurationManager)}, with a plan-mode badge. */
    public static String from(ModelManager models, SessionManager sessions, ConfigurationManager config,
                              boolean planActive) {
        String model = models == null ? null
                : models.getCurrentModel().map(StoredModel::label).orElse(null);
        String session = sessions == null ? null
                : sessions.getCurrentSession().map(Session::name).orElse(null);
        String perm = config == null ? null
                : config.getConfig().getPermissions().resolveMode().name().toLowerCase();
        return format(model, session, perm, planActive);
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        // Strip control chars from user-supplied values (session name / model label) so a stray ESC /
        // CR can't inject ANSI or corrupt the one-line status.
        return value.strip().replaceAll("\\p{Cc}", "");
    }
}
