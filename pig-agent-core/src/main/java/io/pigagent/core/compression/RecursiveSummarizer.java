package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

import java.util.List;

/**
 * Recursive (summary-of-summaries) summarization bounded by a maximum depth.
 *
 * <p>Strategy that wraps the mockable model seam ({@link CompressionService.Summarizer}) plus a
 * {@link TokenEstimator}: it summarizes a batch, and while the resulting summary's own estimate
 * still exceeds the summarized-tier budget it re-summarizes the summary, up to {@code maxDepth}
 * times. The single model touch-point is the injected {@code Summarizer}, so recursion is fully
 * unit-testable offline by passing a fake. A {@code maxDepth <= 0} (or a summary already within
 * budget) degrades to a single-level summary — the prior behavior.
 */
public final class RecursiveSummarizer {

    /** Outcome of a (possibly recursive) summarization: the final text and the depth reached. */
    public record Result(String text, int depth) {
    }

    private final CompressionService.Summarizer summarizer;
    private final TokenEstimator estimator;
    private final int maxDepth;

    public RecursiveSummarizer(CompressionService.Summarizer summarizer, TokenEstimator estimator, int maxDepth) {
        this.summarizer = summarizer;
        this.estimator = estimator;
        this.maxDepth = Math.max(0, maxDepth);
    }

    /**
     * Summarize {@code toSummarize}, recursing while over {@code budgetTokens} until within budget or
     * {@code maxDepth} is reached. Empty input → empty result; a blank first summary short-circuits
     * (caller keeps original context).
     */
    public Result summarize(List<Msg> toSummarize, int budgetTokens) {
        if (toSummarize == null || toSummarize.isEmpty()) {
            return new Result("", 0);
        }
        String summary = summarizer.summarize(toSummarize);
        if (summary == null || summary.isBlank()) {
            return new Result(summary == null ? "" : summary, 0);
        }
        int depth = 0;
        while (depth < maxDepth && budgetTokens > 0
                && estimator.estimate(wrap(summary)) > budgetTokens) {
            String next = summarizer.summarize(wrap(summary));
            if (next == null || next.isBlank() || next.equals(summary)) {
                break; // no progress or aborted → keep last good summary
            }
            summary = next;
            depth++;
        }
        return new Result(summary, depth);
    }

    private static List<Msg> wrap(String summary) {
        return List.of(Msg.builder().name("summary").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(summary).build()).build());
    }
}
