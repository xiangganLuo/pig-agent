package io.pigagent.tool.skills.curator;

import io.agentscope.harness.agent.skill.curator.RejectAllGate;
import io.pigagent.tool.skills.SkillLimits;
import io.pigagent.tool.skills.SkillRegistry;
import io.pigagent.tool.skills.WorkspaceSkillSource;
import io.pigagent.tool.skills.authoring.DefaultSkillContentScanner;
import io.pigagent.tool.skills.authoring.PromotionResult;
import io.pigagent.tool.skills.authoring.SkillGate;
import io.pigagent.tool.skills.authoring.SkillStagingArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 security guard (锁死): the channel/autonomous fail-closed mapping can NEVER promote a skill. The
 * native {@code RejectAllGate} (the channel/autonomous track's gate) returns {@code Defer} — never
 * {@code Approve} — so the {@link NativeSkillPromotionReviewer} yields {@code false}, and a
 * {@link SkillGate} backed by it rejects every promotion (draft stays staged, nothing installed). This
 * locks the {@code autonomous-skills} fail-closed contract under S3.
 *
 * <p>(The stronger by-construction guarantee — channel/autonomous agents hold no {@code SkillGate} at
 * all — is a wiring invariant in {@code AgentBootstrap}; this test locks the gate-mapping half.)
 */
class ChannelPromotionFailClosedTest {

    private static final String SKILL_MD =
            "---\nname: demo\ndescription: a demo skill\n---\n# Demo\nsteps\n";

    @Test
    void rejectAllGate_reviewer_neverApproves() {
        // Arrange — the channel/autonomous track's gate.
        NativeSkillPromotionReviewer reviewer = new NativeSkillPromotionReviewer(new RejectAllGate());

        // Act + Assert — RejectAllGate yields Defer (not Approve) → reviewer returns false.
        assertThat(reviewer.approve("demo", "a demo skill", SKILL_MD))
                .as("RejectAllGate must never approve (fail-closed)").isFalse();
    }

    @Test
    void skillGate_withRejectAllReviewer_rejects_andDoesNotInstall(@TempDir Path ws) throws Exception {
        // Arrange — a staged draft + a SkillGate wired with the RejectAllGate-backed reviewer.
        Path skills = ws.resolve("skills");
        Path pending = skills.resolve(".pending").resolve("demo");
        Files.createDirectories(pending);
        Files.writeString(pending.resolve("SKILL.md"), SKILL_MD);

        SkillStagingArea staging = new SkillStagingArea(skills, ".pending", SkillLimits.defaults());
        WorkspaceSkillSource wsSource = new WorkspaceSkillSource(skills);
        SkillGate gate = new SkillGate(staging, new DefaultSkillContentScanner(),
                new SkillRegistry(List.of(wsSource)), wsSource,
                new NativeSkillPromotionReviewer(new RejectAllGate()), true);

        // Act
        PromotionResult r = gate.promote("demo");

        // Assert — fail-closed: rejected by the gate, draft still staged, nothing installed.
        assertThat(r.status()).isEqualTo(PromotionResult.Status.REJECTED_GATE);
        assertThat(Files.exists(skills.resolve("demo"))).as("must NOT be installed").isFalse();
        assertThat(Files.isRegularFile(pending.resolve("SKILL.md"))).as("draft stays staged").isTrue();
    }
}
