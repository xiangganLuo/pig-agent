package io.pigagent.tool.deferred;

import java.util.Set;

/**
 * The pure decision output of {@link DeferredToolPlanner}: the set of tool names to defer (hide from
 * the model's initial schema). An empty plan means "defer nothing" (feature disabled or nothing
 * matched), which is the backward-compatible default.
 *
 * @param deferredToolNames names to defer; never {@code null}
 */
public record DeferralPlan(Set<String> deferredToolNames) {

    public DeferralPlan {
        deferredToolNames = deferredToolNames == null ? Set.of() : Set.copyOf(deferredToolNames);
    }

    /** The empty plan (defer nothing). */
    public static DeferralPlan empty() {
        return new DeferralPlan(Set.of());
    }

    public boolean isEmpty() {
        return deferredToolNames.isEmpty();
    }
}
