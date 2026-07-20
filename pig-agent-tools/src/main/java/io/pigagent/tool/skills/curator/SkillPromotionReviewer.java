package io.pigagent.tool.skills.curator;

/**
 * The graded promotion-gate decision seam (skill-curator-and-graded-promotion, S3): after
 * {@code SkillGate} runs its content scan + dedup, it delegates the final <b>accept</b> decision here.
 * The default {@link #alwaysApprove()} reproduces today's behavior (the operator's {@code /skill approve}
 * IS the approval), so the feature is off by default with zero behavior change.
 *
 * <p><b>Fail-closed contract:</b> {@link #approve} MUST return {@code false} for anything other than an
 * explicit approval (a native {@code RejectAllGate} returns {@code Defer}, never {@code Approve} — that
 * is a non-approval and MUST reject). Backed by a native
 * {@code io.agentscope.harness.agent.skill.curator.SkillPromotionGate} via
 * {@link NativeSkillPromotionReviewer}: interactive → {@code LocalApprovalGate},
 * channel/autonomous → {@code RejectAllGate} (and, by construction, no {@code SkillGate} at all).
 */
public interface SkillPromotionReviewer {

    /**
     * @return {@code true} iff promotion is approved; {@code false} for defer/reject/error (fail-closed).
     */
    boolean approve(String name, String description, String skillMd);

    /** The default: approve (today's behavior — the operator already decided via {@code /skill approve}). */
    static SkillPromotionReviewer alwaysApprove() {
        return (name, description, skillMd) -> true;
    }
}
