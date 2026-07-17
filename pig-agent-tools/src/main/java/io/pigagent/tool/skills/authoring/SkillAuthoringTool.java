package io.pigagent.tool.skills.authoring;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.availability.Availability;
import io.pigagent.tool.availability.ToolAvailability;
import io.pigagent.tool.contract.ToolErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The agent's write path into the skills stack (autonomous-skills): {@code proposeSkill} distills a
 * solved task/workflow into a {@code SKILL.md} draft and {@code skillManage} manages those drafts —
 * both operate ONLY on the <b>staging area</b> ({@code workspace/skills/.pending/…}). They can
 * <em>never</em> install a skill: this class holds no promoter, so the only way a draft becomes an
 * active skill is the human gate ({@code /skill approve}) or opt-in interactive auto-promotion —
 * fail-closed by construction for channel/autonomous agents.
 *
 * <p>Gated by {@link ToolAvailability} on {@code skills.autonomous.enabled}: when disabled the two
 * tools are hidden from the model schema (zero behavior change). Tools are classified {@code WRITE} in
 * {@code ToolRiskClassifier}, so they are also permission-governed. Failures return the canonical
 * {@code {"error":"…"}} (credential-redacted), never an exception.
 */
public final class SkillAuthoringTool implements ToolAvailability {

    private static final Logger log = LoggerFactory.getLogger(SkillAuthoringTool.class);
    private static final String PROPOSE = "proposeSkill";
    private static final String MANAGE = "skillManage";

    private final SkillStagingArea staging;
    private final SkillContentScanner scanner;
    private final BooleanSupplier enabled;

    public SkillAuthoringTool(SkillStagingArea staging, SkillContentScanner scanner, BooleanSupplier enabled) {
        this.staging = staging;
        this.scanner = scanner == null ? SkillContentScanner.defaults() : scanner;
        this.enabled = enabled == null ? () -> false : enabled;
    }

    @Override
    public java.util.Set<String> availabilityToolNames() {
        return java.util.Set.of(PROPOSE, MANAGE);
    }

    @Override
    public Availability checkAvailability() {
        return enabled.getAsBoolean()
                ? Availability.AVAILABLE
                : Availability.unavailable("skills.autonomous.enabled is off");
    }

    @Tool(description = "Distill a solved task or workflow into a reusable SKILL.md and stage it for "
            + "human review (it is NOT installed until an operator approves it). Use for a genuinely "
            + "reusable, non-trivial workflow — not routine, one-off, secret-bearing, or trivial content.")
    public String proposeSkill(
            @ToolParam(name = "name", description = "Skill name (a short kebab-case identifier)") String name,
            @ToolParam(name = "description", description = "One-line summary of when to use the skill") String description,
            @ToolParam(name = "body", description = "The skill body: a concise, reusable method guide (Markdown)") String body,
            @ToolParam(name = "keywords", description = "Optional comma-separated trigger keywords") String keywords) {
        SkillDraft draft = new SkillDraft(name, description, splitKeywords(keywords), body);
        String skillMd = draft.toSkillMd();
        SkillScanResult scan = scanner.scan(draft.name(), skillMd);
        if (!scan.passed()) {
            return ToolErrors.message("skill rejected: " + String.join("; ", scan.reasons()));
        }
        try {
            staging.stage(draft);
            return "Skill '" + draft.name() + "' staged for review (pending). It is NOT installed until "
                    + "an operator approves it with /skill approve " + draft.name() + ".";
        } catch (IOException e) {
            log.warn("Failed to stage skill '{}': {}", draft.name(), e.toString());
            return ToolErrors.message("failed to stage skill: " + e.getMessage());
        }
    }

    @Tool(description = "Manage staged (pending) skill drafts: list them or discard one. This only "
            + "affects the staging area and never installs a skill.")
    public String skillManage(
            @ToolParam(name = "action", description = "list | discard") String action,
            @ToolParam(name = "name", description = "Skill name (required for 'discard')") String name) {
        String act = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (act) {
            case "list" -> listPending();
            case "discard" -> discard(name);
            default -> ToolErrors.message("unknown action '" + action + "' (use: list | discard)");
        };
    }

    private String listPending() {
        List<String> names = staging.list();
        if (names.isEmpty()) {
            return "No pending skill drafts.";
        }
        StringBuilder sb = new StringBuilder("Pending skill drafts (awaiting review):\n");
        for (String n : names) {
            sb.append("- ").append(n).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String discard(String name) {
        if (name == null || name.isBlank()) {
            return ToolErrors.message("discard requires a skill name");
        }
        return staging.discard(name)
                ? "Discarded staged skill '" + name + "'."
                : ToolErrors.message("no staged skill: " + name);
    }

    private static List<String> splitKeywords(String keywords) {
        List<String> out = new ArrayList<>();
        if (keywords == null || keywords.isBlank()) {
            return out;
        }
        for (String k : keywords.split(",")) {
            String t = k.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
