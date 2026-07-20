package io.pigagent.tool.skills.curator;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link SkillPromotionReviewer} backed by an AgentScope 2.0 native
 * {@link SkillPromotionGate} (adopted as a pure library). It builds a {@link SkillCandidate} from the
 * staged draft (running the native {@link SkillSecurityScanner} for the candidate's verdict) and maps
 * the gate's decision onto pig's boolean accept:
 *
 * <ul>
 *   <li>interactive track → a {@code LocalApprovalGate} (the operator's {@code /skill approve} → Approve);</li>
 *   <li>channel/autonomous track → a {@code RejectAllGate} (returns {@code Defer} — never Approve).</li>
 * </ul>
 *
 * <b>Fail-closed:</b> only an explicit {@code PromotionDecision.Approve} yields {@code true}; a
 * {@code Defer} / {@code Reject} / any exception yields {@code false}, so a non-approval can never
 * promote.
 */
public final class NativeSkillPromotionReviewer implements SkillPromotionReviewer {

    private static final Logger log = LoggerFactory.getLogger(NativeSkillPromotionReviewer.class);

    private final SkillPromotionGate gate;

    public NativeSkillPromotionReviewer(SkillPromotionGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean approve(String name, String description, String skillMd) {
        if (gate == null || name == null || name.isBlank()) {
            return false;
        }
        try {
            SkillSecurityScanner.ScanResult scan = SkillSecurityScanner.scanSingleFile(name, skillMd);
            SkillUsageRecord usage = SkillUsageRecord.newAgentDraft(name);
            SkillCandidate candidate = new SkillCandidate(
                    name, description == null ? "" : description,
                    skillMd == null ? "" : skillMd, List.of(), usage, scan, List.of());
            SkillPromotionGate.PromotionDecision decision =
                    gate.review(candidate, RuntimeContext.empty()).block();
            return decision instanceof SkillPromotionGate.PromotionDecision.Approve;
        } catch (Throwable t) {
            // Fail-closed: any error means "not approved".
            log.warn("Skill promotion review failed for '{}' — treating as not-approved: {}",
                    name, t.toString());
            return false;
        }
    }
}
