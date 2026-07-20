package io.pigagent.core.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.curator.LocalApprovalGate;
import io.agentscope.harness.agent.skill.curator.RejectAllGate;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillCurator;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skill-curator-and-graded-promotion (S3) — the load-bearing SPIKE (tasks group 1, gates the whole
 * S3 line), run offline against the real AgentScope 2.0 harness artifacts (a {@link LocalFilesystem}
 * over a {@code @TempDir}, no model, no agent, none of the native prompt-injection machinery).
 *
 * <p>Builds on S1 ({@code NativeSkillEngineSpikeTest}, which proved the three-piece self-learning
 * engine can be driven as a pure library) by nailing the <b>S3-specific integration points</b> that
 * the design's Decisions depend on:
 * <ol>
 *   <li><b>usage self-feed</b> — pig ("engine, not mouth") must feed usage from its own
 *       {@code loadSkill}; native {@code bumpUse} only increments an EXISTING record, so an unknown
 *       skill must no-op safely and a registered skill increments (D2);</li>
 *   <li><b>.archive compatibility</b> — the curator archives to {@code skills/.archive/} and an
 *       archived skill is NO LONGER surfaced as active by the native repository pig reads through,
 *       while the umbrella dry-run report is non-destructive (D3/D4);</li>
 *   <li><b>fail-closed mapping</b> — {@code RejectAllGate} never yields {@code Approve} (it yields a
 *       {@code Defer} — the S1 R-Spike-1 finding), so the fail-closed test is "not Approve", and
 *       {@code LocalApprovalGate} can be driven to {@code Approve}/reject via a prompter (D6).</li>
 * </ol>
 * If any of these fail at runtime, S3 does NOT proceed to coding — that is the gate.
 */
class SkillCuratorSpikeTest {

    private static Supplier<RuntimeContext> ctx() {
        return RuntimeContext::empty;
    }

    private static final String SKILL_MD =
            "---\nname: %s\ndescription: a demo skill for %s\n---\n# %s\nsteps\n";

    // ---- Spike 1: usage self-feed (D2) --------------------------------------------------------

    @Test
    void usageSelfFed_bumpUseIncrementsExisting_unknownIsNoOp(@TempDir Path ws) {
        // Arrange — only a LocalFilesystem-backed usage store; no middleware/model.
        LocalFilesystem fs = new LocalFilesystem(ws);
        SkillUsageStore store = new SkillUsageStore(fs);

        // Act — a skill promoted by pig is marked agent-created, THEN a loadSkill hit bumps its use.
        store.markAgentCreated("promoted-skill", "agent", List.of());
        store.bumpUse("promoted-skill");
        store.bumpUse("promoted-skill");
        Optional<SkillUsageRecord> registered = store.get("promoted-skill");

        // An unknown skill (hand-authored built-in/user skill with no record) must NOT throw and must
        // NOT create a phantom record — the recorder's no-op-safe contract (D2).
        store.bumpUse("never-registered-skill");
        Optional<SkillUsageRecord> unknown = store.get("never-registered-skill");

        // Assert
        assertThat(registered).as("a marked agent-created skill has a retrievable usage record").isPresent();
        assertThat(registered.get().useCount())
                .as("bumpUse increments an existing record (loadSkill can self-feed usage)")
                .isGreaterThanOrEqualTo(2L);
        assertThat(unknown)
                .as("bumpUse on an unknown skill is a safe no-op (no phantom record, no throw)")
                .isEmpty();
    }

    // ---- Spike 2: .archive compatibility (D3/D4) ----------------------------------------------

    @Test
    void curatorArchive_landsInDotArchive_notSurfacedByNativeRepo(@TempDir Path ws) throws Exception {
        // Arrange — a real workspace skill + a stale agent-created usage record + a curator.
        Path skills = ws.resolve("skills");
        Files.createDirectories(skills.resolve("stale-skill"));
        Files.writeString(skills.resolve("stale-skill").resolve("SKILL.md"),
                String.format(SKILL_MD, "stale-skill", "aging", "Stale Skill"));
        LocalFilesystem fs = new LocalFilesystem(ws);
        SkillUsageStore store = new SkillUsageStore(fs);
        store.markAgentCreated("stale-skill", "agent", List.of());
        // The curator archives via mainRepo.delete(name) (a non-destructive move to .archive), so the
        // repository MUST be WRITABLE — the 3-arg constructor defaults writable=false and would silently
        // ignore the archive (spike finding: SkillCuratorService must build a writable repo).
        WorkspaceSkillRepository repo = new WorkspaceSkillRepository(fs, "skills", ctx(), "workspace", true);
        SkillCuratorConfig config = SkillCuratorConfig.builder()
                .enabled(true).intervalHours(1).minIdleHours(0).staleAfterDays(30).archiveAfterDays(90)
                .build();
        SkillCurator curator = new SkillCurator(fs, store, repo, config);

        // Act 1 — the umbrella dry-run report is NON-destructive (skill still active).
        String dryRunPath = curator.runUmbrellaDryRunReport(Instant.now());
        boolean stillActiveAfterDryRun =
                new FileSystemSkillRepository(skills).getAllSkillNames().contains("stale-skill");

        // Act 2 — drive the curator far into the future so the "now"-created record is well past
        // staleAfterDays (30) AND archiveAfterDays (90). Two passes cover a stale->archive path that
        // some engines split across runs.
        Instant future = Instant.now().plus(200, ChronoUnit.DAYS);
        SkillCurator.CuratorRunReport r1 = curator.runOnce(future);
        SkillCurator.CuratorRunReport r2 = curator.runOnce(future.plus(1, ChronoUnit.DAYS));
        int archived = r1.transitions().archived() + r2.transitions().archived();

        boolean surfacedAfterArchive =
                new FileSystemSkillRepository(skills).getAllSkillNames().contains("stale-skill");
        boolean archiveDirExists = Files.isDirectory(skills.resolve(".archive"));

        // Assert
        assertThat(dryRunPath).as("umbrella dry-run produces a report path").isNotNull();
        assertThat(stillActiveAfterDryRun)
                .as("the dry-run report is non-destructive — the skill is still active").isTrue();
        assertThat(archived).as("the curator archives the stale agent-created skill").isGreaterThanOrEqualTo(1);
        assertThat(surfacedAfterArchive)
                .as("an archived skill is NO LONGER surfaced as active by the native repository")
                .isFalse();
        assertThat(archiveDirExists).as("archived skills land under skills/.archive/").isTrue();
    }

    // ---- Spike 3: fail-closed mapping (D6) ----------------------------------------------------

    @Test
    void failClosedMapping_rejectAllNeverApproves_localApprovalCanApprove() {
        // Arrange — a real candidate from real engine outputs.
        String skillMd = String.format(SKILL_MD, "demo", "review", "Demo");
        SkillSecurityScanner.ScanResult scan = SkillSecurityScanner.scanSingleFile("demo", skillMd);
        SkillUsageRecord usage = SkillUsageRecord.newAgentDraft("demo");
        SkillCandidate candidate = new SkillCandidate(
                "demo", "a demo skill", skillMd, List.of(), usage, scan, List.of());

        // Act + Assert — channel/autonomous track: RejectAllGate NEVER approves (yields Defer per S1).
        SkillPromotionGate.PromotionDecision rejectAll =
                new RejectAllGate().review(candidate, RuntimeContext.empty()).block();
        assertThat(rejectAll)
                .as("RejectAllGate returns a decision that is NEVER Approve (fail-closed, incl. Defer)")
                .isNotNull()
                .isNotInstanceOf(SkillPromotionGate.PromotionDecision.Approve.class);

        // interactive track: LocalApprovalGate driven by an approve-prompter -> Approve.
        LocalApprovalGate.Prompter approve = c -> CompletableFuture.completedFuture(
                new SkillPromotionGate.PromotionDecision.Approve("operator", List.of(), Instant.now()));
        SkillPromotionGate.PromotionDecision approved =
                new LocalApprovalGate(Duration.ofSeconds(5), approve, List.of())
                        .review(candidate, RuntimeContext.empty()).block();
        assertThat(approved)
                .as("LocalApprovalGate maps an operator approval (/skill approve) to Approve")
                .isInstanceOf(SkillPromotionGate.PromotionDecision.Approve.class);

        // interactive track: a reject-prompter -> NOT Approve.
        LocalApprovalGate.Prompter reject = c -> CompletableFuture.completedFuture(
                new SkillPromotionGate.PromotionDecision.Reject("operator said no", "operator"));
        SkillPromotionGate.PromotionDecision rejected =
                new LocalApprovalGate(Duration.ofSeconds(5), reject, List.of())
                        .review(candidate, RuntimeContext.empty()).block();
        assertThat(rejected)
                .as("a rejected review is not Approve")
                .isNotInstanceOf(SkillPromotionGate.PromotionDecision.Approve.class);
    }
}
