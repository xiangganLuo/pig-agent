package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the heuristic importance scorer: user decisions / corrections and errors must
 * score above ordinary chatter, and short acknowledgements score lowest. Pure heuristic, no model.
 */
class HeuristicImportanceScorerTest {

    private final ImportanceScorer scorer = new HeuristicImportanceScorer();

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    private static Msg toolResult(String t) {
        return Msg.builder().name("shell").role(MsgRole.TOOL)
                .content(ToolResultBlock.text(t).withIdAndName("id", "shell")).build();
    }

    @Test
    void decisionOutranksChatter() {
        // Arrange
        Msg decision = user("We must use Postgres instead of MySQL; do not change this.");
        Msg chatter = user("Cool, let me know when the page loads and looks nice.");

        // Act / Assert
        assertThat(scorer.score(decision)).isGreaterThan(scorer.score(chatter));
        assertThat(scorer.isHighImportance(decision)).isTrue();
        assertThat(scorer.isHighImportance(chatter)).isFalse();
    }

    @Test
    void errorToolResultIsHighImportance() {
        // Arrange
        Msg error = toolResult("BUILD FAILED: NullPointerException at Foo.java:42");

        // Act / Assert
        assertThat(scorer.isHighImportance(error)).isTrue();
    }

    @Test
    void correctionIsHighImportance_cjk() {
        // Arrange
        Msg correction = user("其实不要用那个方案，改成用缓存装饰器，这是我的决定。");

        // Act / Assert
        assertThat(scorer.isHighImportance(correction)).isTrue();
    }

    @Test
    void shortAckScoresLowest() {
        // Arrange
        Msg ack = user("ok thanks");

        // Act / Assert
        assertThat(scorer.score(ack)).isZero();
        assertThat(scorer.isHighImportance(ack)).isFalse();
    }

    @Test
    void plainFiller_isLowImportance() {
        // Regression guard: the plain messages used by existing compression tests must NOT be
        // pulled out as high-importance (otherwise default behavior would change).
        assertThat(scorer.isHighImportance(user("message 3"))).isFalse();
        assertThat(scorer.isHighImportance(user("filler 7"))).isFalse();
        assertThat(scorer.isHighImportance(user("please run the build"))).isFalse();
        assertThat(scorer.isHighImportance(toolResult("BUILD SUCCESS, 42 tests"))).isFalse();
    }

    @Test
    void nullOrBlank_isZero() {
        assertThat(scorer.score(null)).isZero();
        assertThat(scorer.score(user("   "))).isZero();
    }
}
