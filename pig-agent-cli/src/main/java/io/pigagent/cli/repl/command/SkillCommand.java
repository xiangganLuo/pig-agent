package io.pigagent.cli.repl.command;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.ReplContext;
import io.pigagent.tool.skills.authoring.PendingSkill;
import io.pigagent.tool.skills.authoring.PromotionResult;
import io.pigagent.tool.skills.authoring.SkillGate;
import org.jline.terminal.Terminal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Optional;

/**
 * {@code /skill} — the operator <b>human gate</b> for autonomous-skills (distinct from the read-only
 * {@code /skills} listing). {@code review} lists staged drafts (or shows one draft's full SKILL.md +
 * scan/dedup verdict); {@code approve <name>} runs scan + dedup + atomic promote; {@code reject
 * <name>} discards a staged draft. Promotion happens ONLY here (or via opt-in interactive
 * auto-promote), never from the agent tools — so channel/autonomous agents are fail-closed.
 */
@Command(name = "/skill", description = "Autonomous-skills human gate (review|approve|reject)")
public final class SkillCommand implements Runnable {

    private final ReplContext ctx;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>", description = "review | approve | reject")
    String action;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<name>")
    String name;

    public SkillCommand(ReplContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Terminal t = ctx.terminal();
        String act = action == null ? "review" : action.toLowerCase();
        // Curator sub-command (skill-curator-and-graded-promotion, S3) is independent of the autonomous-
        // skills gate; handle it first (its own "not enabled" message).
        if ("curator".equals(act)) {
            curator(t);
            return;
        }
        SkillGate gate = ctx.skillGate();
        if (gate == null) {
            Ansi.println(t, Ansi.warn("Autonomous skills are not available."));
            return;
        }
        switch (act) {
            case "review", "list" -> review(t, gate);
            case "approve" -> approve(t, gate);
            case "reject", "discard" -> reject(t, gate);
            case "help" -> usage(t);
            default -> {
                Ansi.println(t, Ansi.error("Unknown action: " + act));
                usage(t);
            }
        }
    }

    /** {@code /skill curator run|status}: run a curator pass, or show a read-only status. */
    private void curator(Terminal t) {
        io.pigagent.tool.skills.curator.SkillCuratorService service = ctx.skillCuratorService();
        if (service == null) {
            Ansi.println(t, Ansi.warn("Skill curator is not enabled (skills.curator.enabled=false)."));
            return;
        }
        String sub = name == null ? "status" : name.trim().toLowerCase();
        io.pigagent.tool.skills.curator.CuratorRunSummary summary = switch (sub) {
            case "run" -> service.runOnce();
            case "status" -> service.status();
            default -> null;
        };
        if (summary == null) {
            Ansi.println(t, Ansi.warn("Usage: /skill curator run|status"));
            return;
        }
        Ansi.println(t, Ansi.heading("Skill curator (" + sub + "):"));
        Ansi.println(t, "  " + summary.describe());
    }

    private void review(Terminal t, SkillGate gate) {
        if (name != null && !name.isBlank()) {
            reviewOne(t, gate, name.trim());
            return;
        }
        List<PendingSkill> pending = gate.listPending();
        Ansi.println(t, Ansi.heading("Pending skills (awaiting review):"));
        if (pending.isEmpty()) {
            Ansi.println(t, Ansi.dim("  (none)"));
            return;
        }
        for (PendingSkill p : pending) {
            String scan = p.scanPassed() ? Ansi.success("scan ok") : Ansi.warn("scan FAILED");
            String desc = p.description().isBlank() ? "" : Ansi.dim(" — " + p.description());
            Ansi.println(t, "  " + Ansi.info(p.name()) + desc
                    + Ansi.dim(" [" + p.sizeBytes() + "B] ") + scan);
        }
        Ansi.println(t, Ansi.dim("  Use /skill review <name> to see one, /skill approve|reject <name> to act."));
    }

    private void reviewOne(Terminal t, SkillGate gate, String skillName) {
        Optional<String> content = gate.review(skillName);
        if (content.isEmpty()) {
            Ansi.println(t, Ansi.error("No staged skill: " + skillName));
            return;
        }
        Ansi.println(t, Ansi.heading("Staged skill: " + skillName));
        Ansi.println(t, Ansi.dim("--- SKILL.md (would be installed) ---"));
        Ansi.println(t, content.get());
        Ansi.println(t, Ansi.dim("-------------------------------------"));
        Ansi.println(t, Ansi.dim("Approve with /skill approve " + skillName
                + " or discard with /skill reject " + skillName + "."));
    }

    private void approve(Terminal t, SkillGate gate) {
        if (name == null || name.isBlank()) {
            Ansi.println(t, Ansi.warn("Usage: /skill approve <name>"));
            return;
        }
        PromotionResult r = gate.promote(name.trim());
        for (String w : r.warnings()) {
            Ansi.println(t, Ansi.warn("  ⚠ " + w));
        }
        switch (r.status()) {
            case PROMOTED -> Ansi.println(t, Ansi.success("Promoted '" + name.trim() + "'.")
                    + (r.overridesBuiltin() ? Ansi.dim(" (overrides a built-in skill)") : "")
                    + Ansi.dim(" Now discoverable via listSkills/loadSkill."));
            case REJECTED_SCAN -> Ansi.println(t, Ansi.error("Rejected (safety scan): "
                    + String.join("; ", r.reasons())));
            case REJECTED_CONFLICT -> Ansi.println(t, Ansi.error("Rejected (name conflict): "
                    + String.join("; ", r.reasons())));
            case NOT_FOUND -> Ansi.println(t, Ansi.error("No staged skill: " + name.trim()));
            case ERROR -> Ansi.println(t, Ansi.error("Promotion failed: " + String.join("; ", r.reasons())));
        }
    }

    private void reject(Terminal t, SkillGate gate) {
        if (name == null || name.isBlank()) {
            Ansi.println(t, Ansi.warn("Usage: /skill reject <name>"));
            return;
        }
        boolean discarded = gate.discard(name.trim());
        Ansi.println(t, discarded
                ? Ansi.success("Discarded staged skill '" + name.trim() + "'.")
                : Ansi.error("No staged skill: " + name.trim()));
    }

    private static void usage(Terminal t) {
        Ansi.println(t, Ansi.heading("/skill actions:"));
        Ansi.println(t, Ansi.dim("  review [name]     list staged drafts, or show one draft's SKILL.md"));
        Ansi.println(t, Ansi.dim("  approve <name>    safety-scan + dedup + atomically install a draft"));
        Ansi.println(t, Ansi.dim("  reject <name>     discard a staged draft"));
        Ansi.println(t, Ansi.dim("  curator run       run a skill-curator pass (aging/archival; dry-run unless auto-archive)"));
        Ansi.println(t, Ansi.dim("  curator status    show skill usage + would-archive candidates (read-only)"));
        Ansi.println(t, Ansi.dim("  (to list installed/available skills, use /skills)"));
    }
}
