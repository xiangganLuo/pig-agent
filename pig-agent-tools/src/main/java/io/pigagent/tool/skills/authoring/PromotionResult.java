package io.pigagent.tool.skills.authoring;

import java.util.List;

/**
 * The outcome of attempting to promote a staged skill (scan + dedup + atomic move). Immutable value
 * type. {@code reasons} explain a rejection (credential-redacted); {@code warnings} are non-blocking
 * notes (e.g. a similar description); {@code overridesBuiltin} is true when the promoted name shadows a
 * built-in skill (the workspace-overrides-built-in rule).
 *
 * @param status           the terminal status of the promotion attempt
 * @param reasons          rejection reasons (empty unless a REJECTED_* status)
 * @param warnings         non-blocking advisories (may be present even on PROMOTED)
 * @param overridesBuiltin true iff the promoted skill shadows a same-named built-in
 */
public record PromotionResult(Status status, List<String> reasons, List<String> warnings,
                              boolean overridesBuiltin) {

    /** Terminal states of a promotion attempt. */
    public enum Status {
        /** Scanned, deduped and atomically installed. */
        PROMOTED,
        /** Rejected by the content safety scan. */
        REJECTED_SCAN,
        /** Rejected because an active workspace skill of the same name exists. */
        REJECTED_CONFLICT,
        /** Rejected by the graded promotion gate (non-Approve decision — fail-closed). */
        REJECTED_GATE,
        /** No staged draft with that name. */
        NOT_FOUND,
        /** An unexpected error during promotion (e.g. a move failure). */
        ERROR
    }

    public PromotionResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean promoted() {
        return status == Status.PROMOTED;
    }

    public static PromotionResult promoted(boolean overridesBuiltin, List<String> warnings) {
        return new PromotionResult(Status.PROMOTED, List.of(), warnings, overridesBuiltin);
    }

    public static PromotionResult of(Status status, List<String> reasons) {
        return new PromotionResult(status, reasons, List.of(), false);
    }
}
