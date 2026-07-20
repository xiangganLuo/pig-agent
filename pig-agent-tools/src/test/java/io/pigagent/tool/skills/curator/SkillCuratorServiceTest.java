package io.pigagent.tool.skills.curator;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.curator.SkillCuratorConfig;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 curator service: dry-run mode is non-destructive (derives would-archive candidates, moves
 * nothing), apply mode really archives stale agent-created skills to {@code .archive}, and status is
 * read-only. Driven over a temp {@link LocalFilesystem} (real native engine, no model).
 */
class SkillCuratorServiceTest {

    private static SkillCuratorConfig config() {
        return SkillCuratorConfig.builder()
                .enabled(true).intervalHours(1).minIdleHours(0).staleAfterDays(30).archiveAfterDays(90)
                .umbrellaPassMode(SkillCuratorConfig.UmbrellaPassMode.DRY_RUN_ONLY)
                .build();
    }

    /** Seed a workspace with one stale, agent-created skill (file + old usage record). */
    private void seedStaleSkill(Path ws) throws Exception {
        Files.createDirectories(ws.resolve("skills").resolve("stale-skill"));
        Files.writeString(ws.resolve("skills").resolve("stale-skill").resolve("SKILL.md"),
                "---\nname: stale-skill\ndescription: an aging skill\n---\n# Stale\nsteps\n");
        SkillUsageStore seed = new SkillUsageStore(new LocalFilesystem(ws));
        Instant old = Instant.now().minus(200, ChronoUnit.DAYS);
        SkillUsageRecord rec = new SkillUsageRecord("agent", 1L, 0L, 0L, old, old, old, old,
                SkillUsageRecord.State.ACTIVE, false, null, null, null, null, java.util.List.of());
        seed.save(Map.of("stale-skill", rec));
    }

    @Test
    void dryRun_derivesStaleCandidates_nonDestructive(@TempDir Path ws) throws Exception {
        // Arrange
        seedStaleSkill(ws);
        SkillCuratorService svc = SkillCuratorService.forWorkspace(ws, config(), false);

        // Act
        CuratorRunSummary summary = svc.runOnce();

        // Assert — flagged as a candidate, but the skill file is untouched (non-destructive).
        assertThat(summary.dryRun()).isTrue();
        assertThat(summary.staleCandidates()).contains("stale-skill");
        assertThat(summary.trackedCount()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(ws.resolve("skills").resolve("stale-skill").resolve("SKILL.md")))
                .as("dry-run must not move the skill").isTrue();
    }

    @Test
    void apply_archivesStaleSkill(@TempDir Path ws) throws Exception {
        // Arrange
        seedStaleSkill(ws);
        SkillCuratorService svc = SkillCuratorService.forWorkspace(ws, config(), true);

        // Act
        CuratorRunSummary summary = svc.runOnce();

        // Assert — a real transition happened and .archive now exists.
        assertThat(summary.dryRun()).isFalse();
        assertThat(summary.archived()).as("stale agent-created skill archived").isGreaterThanOrEqualTo(1);
        assertThat(Files.isDirectory(ws.resolve("skills").resolve(".archive"))).isTrue();
    }

    @Test
    void status_isReadOnly(@TempDir Path ws) throws Exception {
        // Arrange
        seedStaleSkill(ws);
        SkillCuratorService svc = SkillCuratorService.forWorkspace(ws, config(), false);

        // Act
        CuratorRunSummary status = svc.status();

        // Assert — tracked + candidates surfaced, and the skill was NOT moved.
        assertThat(status.dryRun()).isTrue();
        assertThat(status.trackedCount()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(ws.resolve("skills").resolve("stale-skill").resolve("SKILL.md"))).isTrue();
    }
}
