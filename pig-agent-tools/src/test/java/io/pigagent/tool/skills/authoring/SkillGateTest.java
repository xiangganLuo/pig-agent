package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.Skill;
import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillMetadata;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.SkillSource;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillGate: approve→scan+dedup+promote, reject, dedup (workspace conflict / builtin override), auto-promote. */
class SkillGateTest {

    @TempDir
    Path skillsDir;

    private SkillStagingArea staging() {
        return new SkillStagingArea(skillsDir, ".pending", SkillLimits.defaults());
    }

    /** Gate with a WorkspaceSkillSource + a fake built-in source contributing the given skills. */
    private SkillGate gate(SkillStagingArea s, Skill... builtins) {
        WorkspaceSkillSource ws = new WorkspaceSkillSource(skillsDir);
        SkillSource builtinSrc = () -> List.of(builtins);
        SkillRegistry registry = new SkillRegistry(List.of(ws, builtinSrc));
        return new SkillGate(s, SkillContentScanner.defaults(), registry, ws);
    }

    private static SkillDraft draft(String name, String desc, String body) {
        return new SkillDraft(name, desc, List.of("k"), body);
    }

    private static Skill fakeSkill(String name, String description) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String content() { return "# " + name; }
            @Override public SkillMetadata metadata() {
                return new SkillMetadata(name, description, List.of(), "");
            }
        };
    }

    private List<String> workspaceNames() {
        return new WorkspaceSkillSource(skillsDir).discover().stream().map(Skill::name).toList();
    }

    @Test
    void approvePromotesCleanDraftAndMakesItDiscoverable() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("s1", "A useful skill", "# S1\nStep 1."));
        SkillGate g = gate(s);

        PromotionResult r = g.promote("s1");

        assertThat(r.status()).isEqualTo(PromotionResult.Status.PROMOTED);
        assertThat(r.overridesBuiltin()).isFalse();
        assertThat(workspaceNames()).contains("s1");
        assertThat(s.exists("s1")).isFalse();  // moved out of staging
    }

    @Test
    void rejectDiscardsStagedDraft() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("s2", "desc", "# S2\nbody"));
        SkillGate g = gate(s);

        assertThat(g.discard("s2")).isTrue();
        assertThat(s.exists("s2")).isFalse();
    }

    @Test
    void workspaceNameConflictIsRejected() throws IOException {
        Files.createDirectories(skillsDir.resolve("existing"));
        Files.writeString(skillsDir.resolve("existing").resolve("SKILL.md"), "# Existing\nreal skill");
        SkillStagingArea s = staging();
        s.stage(draft("existing", "new desc", "# Existing\nnew body"));
        SkillGate g = gate(s);

        PromotionResult r = g.promote("existing");

        assertThat(r.status()).isEqualTo(PromotionResult.Status.REJECTED_CONFLICT);
        // The real workspace skill was NOT overwritten.
        assertThat(Files.readString(skillsDir.resolve("existing").resolve("SKILL.md"))).contains("real skill");
    }

    @Test
    void builtinNameOnlyIsAllowedAsOverride() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("tdd", "my tdd guide", "# TDD\nmine"));
        SkillGate g = gate(s, fakeSkill("tdd", "built-in tdd guide"));

        PromotionResult r = g.promote("tdd");

        assertThat(r.status()).isEqualTo(PromotionResult.Status.PROMOTED);
        assertThat(r.overridesBuiltin()).isTrue();
        assertThat(workspaceNames()).contains("tdd");
    }

    @Test
    void similarDescriptionYieldsNonBlockingWarning() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("quality-review", "review code for quality and bugs carefully",
                "# QR\nbody"));
        SkillGate g = gate(s, fakeSkill("code-review", "review code for quality and bugs carefully"));

        PromotionResult r = g.promote("quality-review");

        assertThat(r.status()).isEqualTo(PromotionResult.Status.PROMOTED);
        assertThat(r.warnings()).isNotEmpty();
        assertThat(String.join(" ", r.warnings())).contains("code-review");
    }

    @Test
    void scanFailureIsRejectedScan() throws IOException {
        // Stage directly (staging does not scan) a draft whose body carries a credential.
        SkillStagingArea s = staging();
        s.stage(draft("leaky", "desc", "# Leaky\ntoken sk-abcDEF1234567890"));
        SkillGate g = gate(s);

        PromotionResult r = g.promote("leaky");

        assertThat(r.status()).isEqualTo(PromotionResult.Status.REJECTED_SCAN);
        assertThat(s.exists("leaky")).isTrue();  // stays staged for the operator
    }

    @Test
    void notFoundWhenNoStagedDraft() {
        PromotionResult r = gate(staging()).promote("ghost");
        assertThat(r.status()).isEqualTo(PromotionResult.Status.NOT_FOUND);
    }

    @Test
    void autoPromotePendingPromotesOnlyCleanNonConflicting() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("clean1", "desc one useful", "# C1\nbody"));
        s.stage(draft("clean2", "desc two useful", "# C2\nbody"));
        s.stage(draft("leaky", "desc", "# L\nkey sk-abcDEF1234567890"));
        SkillGate g = gate(s);

        List<String> promoted = g.autoPromotePending();

        assertThat(promoted).containsExactlyInAnyOrder("clean1", "clean2");
        assertThat(workspaceNames()).contains("clean1", "clean2").doesNotContain("leaky");
        assertThat(s.exists("leaky")).isTrue();
    }

    @Test
    void listPendingReportsScanVerdictAndDescription() throws IOException {
        SkillStagingArea s = staging();
        s.stage(draft("ok", "a good description", "# OK\nbody"));
        s.stage(draft("bad", "desc", "# Bad\ntoken sk-abcDEF1234567890"));
        SkillGate g = gate(s);

        List<PendingSkill> pending = g.listPending();

        assertThat(pending).extracting(PendingSkill::name).containsExactly("bad", "ok");
        PendingSkill ok = pending.stream().filter(p -> p.name().equals("ok")).findFirst().orElseThrow();
        assertThat(ok.scanPassed()).isTrue();
        assertThat(ok.description()).isEqualTo("a good description");
        PendingSkill bad = pending.stream().filter(p -> p.name().equals("bad")).findFirst().orElseThrow();
        assertThat(bad.scanPassed()).isFalse();
    }
}
