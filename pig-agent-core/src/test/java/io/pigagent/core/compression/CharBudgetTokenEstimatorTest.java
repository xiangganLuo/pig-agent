package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the tool-aware token estimator. The old estimator counted only
 * {@code getTextContent()}, so a message whose payload is a big tool result estimated near-zero and
 * compression triggered far too late. These lock in that tool content is now counted.
 */
class CharBudgetTokenEstimatorTest {

    private final TokenEstimator estimator = new CharBudgetTokenEstimator();

    private static Msg text(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    private static Msg toolResult(String payload) {
        return Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text(payload).withIdAndName("id", "shell")).build();
    }

    @Test
    void estimate_countsToolResultContent_farHigherThanTextOnly() {
        // Arrange: a tiny text message vs. a message carrying a 4000-char tool result.
        List<Msg> textOnly = List.of(text("hi"));
        List<Msg> withToolResult = List.of(text("hi"), toolResult("R".repeat(4000)));

        // Act
        int textEstimate = estimator.estimate(textOnly);
        int toolEstimate = estimator.estimate(withToolResult);

        // Assert: the tool result dominates the estimate (~4000/4 ≈ 1000 tokens), not dropped.
        assertThat(toolEstimate).isGreaterThan(textEstimate + 800);
    }

    @Test
    void estimate_textOnly_matchesCharHeuristic() {
        // Arrange: 40 chars → ~10 tokens at 4 chars/token.
        List<Msg> messages = List.of(text("a".repeat(40)));

        // Act
        int estimate = estimator.estimate(messages);

        // Assert
        assertThat(estimate).isEqualTo(10);
    }

    @Test
    void estimate_nullOrEmpty_isZero() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate(List.of())).isZero();
    }
}
