package io.pigagent.tool.skills.authoring;

import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import io.pigagent.tool.skills.curator.SkillPromotionReviewer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 graded promotion gate: {@code SkillGate.promote} delegates the accept decision to the injected
 * {@link SkillPromotionReviewer} after scan + dedup. Default {@code alwaysApprove()} reproduces today's
 * behavior; a reviewer that does not approve (defer/reject — the channel/autonomous {@code RejectAllGate}
 * mapping) yields {@code REJECTED_GATE} and leaves the draft staged, uninstalled (fail-closed).
 */
class SkillGateGradedGateTest {

    private static final String SKILL_MD =
            "---\nname: demo\ndescription: a demo skill\n---\n# Demo\nsteps\n";

    private SkillGate gate(Path ws, SkillPromotionReviewer reviewer) {
        SkillStagingArea staging = new SkillStagingArea(ws.resolve("skills"), ".pending", SkillLimits.defaults());
        SkillRegistry registry = new SkillRegistry(List.of(new WorkspaceSkillSource(ws.resolve("skills"))));
        return new SkillGate(staging, new DefaultSkillContentScanner(), registry,
                new WorkspaceSkillSource(ws.resolve("skills")), reviewer, false);
    }

    private void stage(Path ws, String name) throws IOException {
        Path dir = ws.resolve("skills").resolve(".pending").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), SKILL_MD);
    }

    @Test
    void defaultAlwaysApprove_promotesAfterScanAndDedup(@TempDir Path ws) throws Exception {
        // Arrange
        stage(ws, "demo");
        SkillGate gate = gate(ws, SkillPromotionReviewer.alwaysApprove());

        // Act
        PromotionResult r = gate.promote("demo");

        // Assert — today's behavior: promoted + installed.
        assertThat(r.status()).isEqualTo(PromotionResult.Status.PROMOTED);
        assertThat(Files.isRegularFile(ws.resolve("skills").resolve("demo").resolve("SKILL.md"))).isTrue();
    }

    @Test
    void reviewerRejects_yieldsRejectedGate_andLeavesDraftStaged(@TempDir Path ws) throws Exception {
        // Arrange — a reviewer that never approves (the channel/autonomous RejectAllGate mapping).
        stage(ws, "demo");
        SkillGate gate = gate(ws, (name, description, skillMd) -> false);

        // Act
        PromotionResult r = gate.promote("demo");

        // Assert — fail-closed: not installed, draft still staged.
        assertThat(r.status()).isEqualTo(PromotionResult.Status.REJECTED_GATE);
        assertThat(Files.exists(ws.resolve("skills").resolve("demo"))).isFalse();
        assertThat(Files.isRegularFile(
                ws.resolve("skills").resolve(".pending").resolve("demo").resolve("SKILL.md"))).isTrue();
    }

    @Test
    void reviewerConsultedOnlyAfterScanAndDedup(@TempDir Path ws) throws Exception {
        // Arrange — a reviewer that records whether it was called.
        stage(ws, "demo");
        boolean[] called = {false};
        SkillGate gate = gate(ws, (name, description, skillMd) -> {
            called[0] = true;
            return true;
        });

        // Act
        gate.promote("demo");

        // Assert — reviewer participated (scan + dedup passed first).
        assertThat(called[0]).isTrue();
    }
}
