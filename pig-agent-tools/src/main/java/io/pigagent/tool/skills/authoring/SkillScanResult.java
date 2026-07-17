package io.pigagent.tool.skills.authoring;

import java.util.List;

/**
 * The outcome of a {@link SkillContentScanner} pass over a staged skill: whether it is safe to promote
 * plus the human-readable reasons it was rejected (empty when it passed). Reasons name the defect
 * category only (e.g. "contains credential-like content") and MUST NOT echo any secret value.
 *
 * @param passed  true iff every safety check passed (safe to promote)
 * @param reasons the rejection reasons (empty when {@code passed})
 */
public record SkillScanResult(boolean passed, List<String> reasons) {

    public SkillScanResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** A passing result (no reasons). */
    public static SkillScanResult ok() {
        return new SkillScanResult(true, List.of());
    }

    /** A failing result carrying the given reasons. */
    public static SkillScanResult rejected(List<String> reasons) {
        return new SkillScanResult(false, reasons);
    }
}
