package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for recursive (summary-of-summaries) summarization. The model seam is a deterministic
 * fake ({@link HalvingSummarizer}) so the whole thing runs offline.
 */
class RecursiveSummarizerTest {

    private final TokenEstimator estimator = new CharBudgetTokenEstimator();

    /** Deterministic fake: each summary is half the rendered length of its input (min 1 char). */
    static final class HalvingSummarizer implements CompressionService.Summarizer {
        int calls = 0;

        @Override
        public String summarize(List<Msg> older) {
            calls++;
            int len = 0;
            for (Msg m : older) {
                len += MsgContentRenderer.render(m, 0).length();
            }
            return "S".repeat(Math.max(1, len / 2));
        }
    }

    private static Msg text(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    private int estimateOf(String summary) {
        return estimator.estimate(List.of(text(summary)));
    }

    @Test
    void recursesUntilWithinBudget() {
        // Arrange: a big batch, small summarized-tier budget, generous depth.
        List<Msg> older = List.of(text("A".repeat(4000)));
        RecursiveSummarizer rec = new RecursiveSummarizer(new HalvingSummarizer(), estimator, 10);

        // Act
        RecursiveSummarizer.Result r = rec.summarize(older, 30);

        // Assert: it recursed at least once and the final summary fits the budget.
        assertThat(r.depth()).isGreaterThanOrEqualTo(1);
        assertThat(estimateOf(r.text())).isLessThanOrEqualTo(30);
    }

    @Test
    void respectsMaxDepth() {
        // Arrange: budget so tiny it can never be satisfied → recursion is bounded only by maxDepth.
        List<Msg> older = List.of(text("A".repeat(4000)));
        RecursiveSummarizer rec = new RecursiveSummarizer(new HalvingSummarizer(), estimator, 2);

        // Act
        RecursiveSummarizer.Result r = rec.summarize(older, 1);

        // Assert: stops exactly at the max depth.
        assertThat(r.depth()).isEqualTo(2);
    }

    @Test
    void noRecursionWhenWithinBudget() {
        // Arrange: huge budget → the first summary already fits.
        HalvingSummarizer fake = new HalvingSummarizer();
        List<Msg> older = List.of(text("A".repeat(4000)));
        RecursiveSummarizer rec = new RecursiveSummarizer(fake, estimator, 5);

        // Act
        RecursiveSummarizer.Result r = rec.summarize(older, 100_000);

        // Assert: single summary, no recursion.
        assertThat(r.depth()).isZero();
        assertThat(fake.calls).isEqualTo(1);
    }

    @Test
    void blankSummaryShortCircuits() {
        // Arrange
        CompressionService.Summarizer blank = older -> "";
        List<Msg> older = List.of(text("A".repeat(4000)));

        // Act
        RecursiveSummarizer.Result r = new RecursiveSummarizer(blank, estimator, 5).summarize(older, 1);

        // Assert: blank summary aborts recursion; caller keeps original.
        assertThat(r.text()).isBlank();
        assertThat(r.depth()).isZero();
    }

    @Test
    void emptyInputIsEmptyResult() {
        RecursiveSummarizer rec = new RecursiveSummarizer(new HalvingSummarizer(), estimator, 5);
        RecursiveSummarizer.Result r = rec.summarize(List.of(), 30);
        assertThat(r.text()).isEmpty();
        assertThat(r.depth()).isZero();
    }
}
