package io.pigagent.core.skill;

import io.agentscope.core.agent.RuntimeContext;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * native-skill-engine-bridge — the load-bearing SPIKE (tasks group 1, gates the whole skill line),
 * run offline against the real AgentScope 2.0 harness artifacts (a {@link LocalFilesystem} over a
 * {@code @TempDir}, no model, no agent).
 *
 * <p>It proves the S2/S3 feasibility claim javap established at the signature level: the native
 * self-learning-loop engine — {@link SkillUsageStore} / {@link SkillCurator} / {@link SkillPromotionGate}
 * — can be constructed and <b>driven as a pure library</b>, "engine, not mouth": with NONE of the
 * native prompt-injection machinery installed ({@code DynamicSkillMiddleware} /
 * {@code AgentSkillPromptProvider} / {@code HarnessSkillMiddleware} / {@code SkillLoadTool} /
 * {@code enableSkillManageTool} / {@code enableSkillCurator}). No {@code <available_skills>} block, no
 * native skill tools, no LLM — just the filesystem + a {@link RuntimeContext} supplier.
 *
 * <p>If any of these fail at runtime (contradicting the signature-level conclusion) the whole skill
 * line does NOT proceed to coding — that is the gate.
 */
class NativeSkillEngineSpikeTest {

    /** An empty RuntimeContext supplier — the whole context floor the repository needs. */
    private static Supplier<RuntimeContext> ctx() {
        return RuntimeContext::empty;
    }

    @Test
    void usageStore_drivesStandalone_noMiddleware(@TempDir Path ws) {
        // Arrange — only a LocalFilesystem; nothing agent/model/middleware related.
        LocalFilesystem fs = new LocalFilesystem(ws);
        SkillUsageStore store = new SkillUsageStore(fs);

        // Act — a fresh store loads (empty), then a record is registered and its usage bumped.
        // (Native semantics, confirmed by the spike: bumpUse increments an EXISTING record only, so
        // an unknown skill must be registered via markAgentDraft first — usage tracking is scoped to
        // skills the engine already knows about.)
        Map<String, SkillUsageRecord> initial = store.load();
        store.markAgentDraft("demo-skill", "spike-agent");
        store.bumpUse("demo-skill");
        SkillUsageRecord rec = store.get("demo-skill").orElse(null);

        // Assert — the store reads/writes the filesystem as a plain library, no prompt path involved.
        assertThat(initial).as("a fresh usage store loads an (empty) map without throwing").isNotNull();
        assertThat(rec).as("a registered record is persisted and retrievable by name").isNotNull();
        assertThat(rec.useCount()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void curator_runsOnce_standalone_noMiddleware(@TempDir Path ws) throws Exception {
        // Arrange — LocalFilesystem + usage store + workspace skill repo + curator config. No agent.
        Files.createDirectories(ws.resolve("skills"));
        LocalFilesystem fs = new LocalFilesystem(ws);
        SkillUsageStore store = new SkillUsageStore(fs);
        WorkspaceSkillRepository repo = new WorkspaceSkillRepository(fs, "skills", ctx());
        SkillCuratorConfig config = SkillCuratorConfig.builder()
                .enabled(true).intervalHours(1).minIdleHours(1).staleAfterDays(30).archiveAfterDays(90)
                .build();
        SkillCurator curator = new SkillCurator(fs, store, repo, config);

        // Act — drive one curator pass directly (no reasoning loop, no <available_skills>).
        SkillCurator.CuratorRunReport report = curator.runOnce(Instant.now());

        // Assert — the curator ran to completion as a pure library.
        assertThat(report).as("SkillCurator.runOnce returns a report when driven standalone").isNotNull();
        assertThat(report.ranAt()).isNotNull();
        assertThat(report.transitions()).as("a transitions summary is produced").isNotNull();
    }

    @Test
    void rejectAllGate_reviews_standalone_noMiddleware() {
        // Arrange — build a real SkillCandidate from real engine outputs (scanner + usage record).
        String skillMd = "---\nname: demo\ndescription: a demo skill\n---\n# Demo\nsteps\n";
        SkillSecurityScanner.ScanResult scan = SkillSecurityScanner.scanSingleFile("demo", skillMd);
        SkillUsageRecord usage = SkillUsageRecord.newAgentDraft("demo");
        SkillCandidate candidate = new SkillCandidate(
                "demo", "a demo skill", skillMd, List.of(), usage, scan, List.of());

        // Act — the non-interactive fail-closed gate reviews with only a RuntimeContext.
        SkillPromotionGate gate = new RejectAllGate();
        SkillPromotionGate.PromotionDecision decision =
                gate.review(candidate, RuntimeContext.empty()).block();

        // Assert — the gate is a standalone pure library that returns a decision, and it is
        // FAIL-CLOSED: it NEVER auto-approves. (Spike finding: RejectAllGate yields a Defer whose
        // reason is "promotion requires explicit HarnessAgent.promoteSkill call by an authorized
        // caller" — same guarantee as a Reject: nothing is promoted without an explicit authorized
        // call. This is the S3 channel/autonomous fail-closed direction; interactive uses
        // LocalApprovalGate.)
        assertThat(scan).as("the pure-static security scanner produced a verdict").isNotNull();
        assertThat(scan.verdict()).isNotNull();
        assertThat(decision).as("RejectAllGate returns a decision when driven standalone").isNotNull();
        assertThat(decision).as("the non-interactive gate NEVER auto-approves (fail-closed)")
                .isNotInstanceOf(SkillPromotionGate.PromotionDecision.Approve.class);
        // The interactive gate is likewise constructible with no middleware (driving it needs a
        // prompter/stdin, exercised in S3, not here).
        assertThat(new LocalApprovalGate()).as("the interactive approval gate is constructible standalone")
                .isNotNull();
    }
}
