package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillManifestParser;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import io.pigagent.tool.skills.curator.SkillPromotionReviewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The orchestration façade of the autonomous-skills write path: it turns a <b>human decision</b>
 * (approve / reject) or an opt-in interactive auto-promotion into "scan + dedup + atomic promote". It
 * is the single home of the promotion policy — the agent-facing {@code proposeSkill}/{@code skillManage}
 * tools deliberately hold NO reference to it, so there is no code path by which the agent (or a
 * channel/autonomous track) can promote a skill (fail-closed by construction).
 *
 * <p><b>Dedup rule</b> (conservative, reusing the existing {@link SkillRegistry}): a name that already
 * exists as an <em>active workspace skill</em> is rejected ({@link PromotionResult.Status#REJECTED_CONFLICT}
 * — never clobber a real user skill); a name matching only a <em>built-in</em> is allowed and flagged
 * {@code overridesBuiltin} (the workspace-overrides-built-in rule); a description close to an existing
 * skill's yields a non-blocking warning.
 */
public final class SkillGate {

    private static final Logger log = LoggerFactory.getLogger(SkillGate.class);
    private static final double SIMILAR_DESCRIPTION_THRESHOLD = 0.6;
    private static final int MIN_TOKENS_FOR_SIMILARITY = 3;

    private final SkillStagingArea staging;
    private final SkillContentScanner scanner;
    private final SkillRegistry existingSkills;
    private final WorkspaceSkillSource workspaceSource;
    private final SkillManifestParser parser = SkillManifestParser.defaults();
    private final SkillPromotionReviewer reviewer;
    private final boolean umbrellaMergeEnabled;

    public SkillGate(SkillStagingArea staging, SkillContentScanner scanner,
                     SkillRegistry existingSkills, WorkspaceSkillSource workspaceSource) {
        this(staging, scanner, existingSkills, workspaceSource,
                SkillPromotionReviewer.alwaysApprove(), false);
    }

    /**
     * Full constructor (skill-curator-and-graded-promotion, S3): additionally wire the graded promotion
     * {@code reviewer} (the native {@code SkillPromotionGate} decision; default {@code alwaysApprove()}
     * reproduces today's {@code /skill approve}) and {@code umbrellaMergeEnabled} — when true the
     * curator's semantic umbrella-merge replaces the hand-rolled Jaccard description warning, so the
     * legacy Jaccard warning is suppressed here.
     */
    public SkillGate(SkillStagingArea staging, SkillContentScanner scanner,
                     SkillRegistry existingSkills, WorkspaceSkillSource workspaceSource,
                     SkillPromotionReviewer reviewer, boolean umbrellaMergeEnabled) {
        this.staging = staging;
        this.scanner = scanner == null ? SkillContentScanner.defaults() : scanner;
        this.existingSkills = existingSkills;
        this.workspaceSource = workspaceSource;
        this.reviewer = reviewer == null ? SkillPromotionReviewer.alwaysApprove() : reviewer;
        this.umbrellaMergeEnabled = umbrellaMergeEnabled;
    }

    /** Staged drafts awaiting review, each with its description, size and current scan verdict. */
    public List<PendingSkill> listPending() {
        List<PendingSkill> out = new ArrayList<>();
        for (String name : staging.list()) {
            String content = staging.read(name).orElse("");
            String description = parser.parse(content, name).metadata().description();
            boolean scanOk = scanner.scan(name, content).passed();
            out.add(new PendingSkill(name, description, staging.size(name), scanOk));
        }
        return out;
    }

    /** The full staged {@code SKILL.md} for a pending draft (for the operator to eyeball before approve). */
    public Optional<String> review(String name) {
        return staging.read(name);
    }

    /** Discard a staged draft (reject). Returns true iff something was removed. */
    public boolean discard(String name) {
        return staging.discard(name);
    }

    /**
     * Approve = scan + dedup + atomic promote. On success the draft is installed to
     * {@code workspace/skills/<name>/}, where {@code WorkspaceSkillSource} discovers it on the next scan.
     */
    public PromotionResult promote(String name) {
        Optional<String> staged = staging.read(name);
        if (staged.isEmpty()) {
            return PromotionResult.of(PromotionResult.Status.NOT_FOUND, List.of("no staged skill: " + name));
        }
        String content = staged.get();
        SkillScanResult scan = scanner.scan(name, content);
        if (!scan.passed()) {
            return PromotionResult.of(PromotionResult.Status.REJECTED_SCAN, scan.reasons());
        }
        Set<String> workspaceNames = workspaceNames();
        if (workspaceNames.contains(name)) {
            return PromotionResult.of(PromotionResult.Status.REJECTED_CONFLICT,
                    List.of("a workspace skill named '" + name + "' already exists"));
        }
        // Graded promotion gate (S3): after scan + dedup, delegate the accept decision to the reviewer.
        // A non-Approve (defer/reject) is fail-closed → REJECTED_GATE. Default reviewer approves, so the
        // interactive /skill approve behavior is unchanged.
        String description = parser.parse(content, name).metadata().description();
        if (!reviewer.approve(name, description, content)) {
            return PromotionResult.of(PromotionResult.Status.REJECTED_GATE,
                    List.of("promotion gate did not approve '" + name + "'"));
        }
        boolean overridesBuiltin = allNames().contains(name);
        // Umbrella-merge (S3) supersedes the hand-rolled Jaccard description warning when enabled.
        List<String> warnings = umbrellaMergeEnabled ? List.of() : similarityWarnings(name, content);
        try {
            staging.promote(name);
            return PromotionResult.promoted(overridesBuiltin, warnings);
        } catch (IOException e) {
            log.warn("Failed to promote staged skill '{}': {}", name, e.toString());
            return PromotionResult.of(PromotionResult.Status.ERROR, List.of("promotion failed: " + e.getMessage()));
        }
    }

    /**
     * Promote every staged draft that passes scan + dedup, returning the promoted names. Used only by the
     * interactive REPL when {@code auto-promote} is on; channel/autonomous tracks never call this.
     */
    public List<String> autoPromotePending() {
        List<String> promoted = new ArrayList<>();
        for (String name : staging.list()) {
            if (promote(name).promoted()) {
                promoted.add(name);
            }
        }
        return promoted;
    }

    private Set<String> workspaceNames() {
        Set<String> names = new HashSet<>();
        if (workspaceSource == null) {
            return names;
        }
        for (Skill s : workspaceSource.discover()) {
            if (s != null && s.name() != null) {
                names.add(s.name());
            }
        }
        return names;
    }

    private Set<String> allNames() {
        if (existingSkills == null) {
            return Set.of();
        }
        return new HashSet<>(existingSkills.listNames());
    }

    /** Non-blocking warnings when the draft description closely matches an existing skill's. */
    private List<String> similarityWarnings(String name, String content) {
        if (existingSkills == null) {
            return List.of();
        }
        String draftDesc = parser.parse(content, name).metadata().description();
        Set<String> draftTokens = tokens(draftDesc);
        if (draftTokens.size() < MIN_TOKENS_FOR_SIMILARITY) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (Skill s : existingSkills.all()) {
            if (s == null || name.equals(s.name())) {
                continue;
            }
            String otherDesc;
            try {
                otherDesc = s.metadata().description();
            } catch (RuntimeException e) {
                continue;
            }
            if (jaccard(draftTokens, tokens(otherDesc)) >= SIMILAR_DESCRIPTION_THRESHOLD) {
                warnings.add("description is similar to existing skill '" + s.name() + "'");
            }
        }
        return warnings;
    }

    private static Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() > 2) {
                out.add(t);
            }
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
