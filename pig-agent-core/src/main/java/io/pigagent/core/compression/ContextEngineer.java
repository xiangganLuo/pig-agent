package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Structured context-engineering brain: turns a full in-memory conversation into a rewritten
 * message list (three-tier budget + importance retention + verbatim protection + recursive
 * summarization + a consistency check with safe fallback). Pure except for the summarizer seam, so
 * every path is unit-testable offline with a mock summarizer.
 *
 * <p>Returns {@code null} to mean "do not compress this round; keep the original context" — used
 * for nothing-to-do, a blank summary, and the ultimate safety fallback when even the safe profile
 * cannot preserve critical content. {@link CompressionService} owns the actual {@code Memory}
 * mutation and lineage recording.
 */
public final class ContextEngineer {

    private static final Logger log = LoggerFactory.getLogger(ContextEngineer.class);

    private static final String SUMMARY_NAME = "summary";
    private static final String SUMMARY_PREFIX = "[Earlier conversation summary]\n";

    private final CompressionService.Summarizer summarizer;
    private final TokenEstimator estimator;
    private final ImportanceScorer scorer;
    private final VerbatimGuard guard;
    private final ConsistencyChecker checker;
    private final EngineeringOptions options;

    public ContextEngineer(CompressionService.Summarizer summarizer, TokenEstimator estimator,
                           ImportanceScorer scorer, VerbatimGuard guard, EngineeringOptions options) {
        this.summarizer = summarizer;
        this.estimator = estimator;
        this.scorer = scorer;
        this.guard = guard;
        this.checker = new ConsistencyChecker(guard);
        this.options = options == null ? EngineeringOptions.defaults() : options;
    }

    /** Default component wiring (char-budget estimator + heuristic scorer + verbatim guard). */
    public static ContextEngineer withDefaults(CompressionService.Summarizer summarizer, EngineeringOptions options) {
        return new ContextEngineer(summarizer, new CharBudgetTokenEstimator(),
                new HeuristicImportanceScorer(), new VerbatimGuard(), options);
    }

    public int keepRecent() {
        return options.keepRecent();
    }

    /**
     * Produce the rewritten message list, or {@code null} to keep the original context.
     *
     * @param messages     the full in-memory conversation
     * @param budgetTokens total context token budget (split into tiers via {@link ContextBudget})
     */
    public List<Msg> rewrite(List<Msg> messages, int budgetTokens) {
        int keepRecent = options.keepRecent();
        if (messages == null || messages.size() <= keepRecent) {
            return null;
        }
        ContextBudget budget = ContextBudget.allocate(budgetTokens, options.ratios());

        List<Msg> candidate = build(messages, keepRecent, budget, options);
        if (candidate == null) {
            return null; // blank summary → keep original (existing contract)
        }
        if (!options.consistencyCheck()) {
            return candidate;
        }
        List<Msg> required = requiredSurvivors(messages, keepRecent);
        if (checker.check(required, candidate).ok()) {
            return candidate;
        }

        // Consistency failed → fall back to the safer, less-aggressive profile and re-check.
        log.warn("Context compression consistency check failed; falling back to safer behavior");
        EngineeringOptions safe = options.safe();
        List<Msg> safeCandidate = build(messages, keepRecent, budget, safe);
        if (safeCandidate != null && checker.check(required, safeCandidate).ok()) {
            return safeCandidate;
        }
        // Even the safe profile cannot preserve everything → keep original, never drop silently.
        int missing = safeCandidate == null ? required.size()
                : checker.check(required, safeCandidate).missing().size();
        log.warn("Safe-fallback compression still inconsistent ({} critical item(s) at risk); "
                + "keeping original context uncompressed", missing);
        return null;
    }

    /** Build a compression candidate for the given options, or null if the summary came back blank. */
    private List<Msg> build(List<Msg> messages, int keepRecent, ContextBudget budget, EngineeringOptions opts) {
        int splitAt = messages.size() - keepRecent;
        List<Msg> older = messages.subList(0, splitAt);
        List<Msg> recent = messages.subList(splitAt, messages.size());

        Set<Msg> keep = selectVerbatim(older, opts);

        List<Msg> verbatimKeep = new ArrayList<>();
        List<Msg> toSummarize = new ArrayList<>();
        for (Msg m : older) {
            if (keep.contains(m)) {
                verbatimKeep.add(m);
            } else {
                toSummarize.add(m);
            }
        }

        int recursionDepth = opts.recursiveSummary() ? opts.maxSummaryDepth() : 0;
        RecursiveSummarizer recursive = new RecursiveSummarizer(summarizer, estimator, recursionDepth);
        RecursiveSummarizer.Result summary = recursive.summarize(toSummarize, budget.summarizedTokens());
        if (!toSummarize.isEmpty() && (summary.text() == null || summary.text().isBlank())) {
            return null; // blank summary → abort (caller keeps original)
        }

        List<Msg> candidate = new ArrayList<>();
        if (!toSummarize.isEmpty() && summary.text() != null && !summary.text().isBlank()) {
            candidate.add(summaryMsg(summary.text()));
        }
        candidate.addAll(verbatimKeep);
        candidate.addAll(recent);
        return candidate;
    }

    /** Decide which older messages to keep verbatim (high-importance ∪ protected). */
    private Set<Msg> selectVerbatim(List<Msg> older, EngineeringOptions opts) {
        Set<Msg> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Msg m : older) {
            boolean protectedContent = opts.verbatimProtection() && guard.isProtected(m);
            boolean important = opts.importanceRetention() && scorer.isHighImportance(m);
            if (protectedContent || important) {
                keep.add(m);
            }
        }
        return keep;
    }

    private List<Msg> requiredSurvivors(List<Msg> messages, int keepRecent) {
        List<Msg> older = messages.subList(0, messages.size() - keepRecent);
        List<Msg> required = new ArrayList<>();
        for (Msg m : older) {
            if (guard.isProtected(m) || scorer.isHighImportance(m)) {
                required.add(m);
            }
        }
        return required;
    }

    private static Msg summaryMsg(String text) {
        return Msg.builder().name(SUMMARY_NAME).role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(SUMMARY_PREFIX + text).build())
                .build();
    }
}
