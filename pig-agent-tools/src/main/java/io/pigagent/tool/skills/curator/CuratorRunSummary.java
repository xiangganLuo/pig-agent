package io.pigagent.tool.skills.curator;

import java.time.Instant;
import java.util.List;

/**
 * Immutable result of a curator pass or a read-only status query (skill-curator-and-graded-promotion,
 * S3). In <b>dry-run</b> mode ({@code auto-archive=false}) only {@link #staleCandidates()} (the skills
 * that <em>would</em> be archived) + the umbrella report path are populated and no migration happens;
 * in <b>apply</b> mode the transition counts reflect real {@code stale→.archive} moves.
 */
public record CuratorRunSummary(
        boolean dryRun,
        int checked,
        int markedStale,
        int archived,
        int reactivated,
        int trackedCount,
        List<String> staleCandidates,
        String dryRunReportPath,
        Instant ranAt) {

    public CuratorRunSummary {
        staleCandidates = staleCandidates == null ? List.of() : List.copyOf(staleCandidates);
    }

    /** A real (destructive) run: transition counts came from the native curator. */
    public static CuratorRunSummary applied(int checked, int markedStale, int archived, int reactivated,
                                            int trackedCount, List<String> staleCandidates, Instant ranAt) {
        return new CuratorRunSummary(false, checked, markedStale, archived, reactivated,
                trackedCount, staleCandidates, null, ranAt);
    }

    /** A non-destructive dry run: suggestions only. */
    public static CuratorRunSummary dryRun(int trackedCount, List<String> staleCandidates,
                                           String dryRunReportPath, Instant ranAt) {
        return new CuratorRunSummary(true, 0, 0, 0, 0,
                trackedCount, staleCandidates, dryRunReportPath, ranAt);
    }

    /** A read-only status query (no run). */
    public static CuratorRunSummary status(int trackedCount, List<String> staleCandidates, Instant ranAt) {
        return new CuratorRunSummary(true, 0, 0, 0, 0,
                trackedCount, staleCandidates, null, ranAt);
    }

    /** A single-line human summary for {@code /skill curator}. */
    public String describe() {
        if (dryRun) {
            return "dry-run: " + trackedCount + " tracked, " + staleCandidates.size()
                    + " would-archive candidate(s)"
                    + (staleCandidates.isEmpty() ? "" : " " + staleCandidates);
        }
        return "applied: checked=" + checked + " markedStale=" + markedStale
                + " archived=" + archived + " reactivated=" + reactivated
                + " (" + trackedCount + " tracked)";
    }
}
