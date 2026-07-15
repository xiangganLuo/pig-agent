package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the post-compression consistency check: required (pinned/high-importance) content
 * and protected verbatim spans must survive in the candidate; otherwise the check flags them.
 */
class ConsistencyCheckerTest {

    private final ConsistencyChecker checker = new ConsistencyChecker(new VerbatimGuard());

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    private static Msg summary(String t) {
        return Msg.builder().name("summary").role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(t).build()).build();
    }

    @Test
    void okWhenRequiredMessageSurvives() {
        // Arrange
        Msg fact = user("We must use Postgres instead of MySQL");
        List<Msg> candidate = List.of(summary("earlier stuff"), fact, user("recent"));

        // Act
        ConsistencyChecker.Result r = checker.check(List.of(fact), candidate);

        // Assert
        assertThat(r.ok()).isTrue();
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void flagsMissingRequiredMessage() {
        // Arrange: the fact is NOT in the candidate (it was summarized away into "SUMMARY").
        Msg fact = user("We must use Postgres instead of MySQL");
        List<Msg> candidate = List.of(summary("SUMMARY"), user("recent"));

        // Act
        ConsistencyChecker.Result r = checker.check(List.of(fact), candidate);

        // Assert
        assertThat(r.ok()).isFalse();
        assertThat(r.missing()).isNotEmpty();
    }

    @Test
    void checksProtectedSpansSurvive() {
        // Arrange: a required message carrying a protected span (hash).
        Msg withHash = user("verify commit 3cb26d9e1f0a before merge");
        List<Msg> present = List.of(summary("note: 3cb26d9e1f0a"), user("recent"));
        List<Msg> absent = List.of(summary("SUMMARY"), user("recent"));

        // Act / Assert: span present → ok; span gone → flagged.
        assertThat(checker.check(List.of(withHash), present).ok()).isTrue();
        assertThat(checker.check(List.of(withHash), absent).ok()).isFalse();
    }

    @Test
    void emptyRequiredIsOk() {
        assertThat(checker.check(List.of(), List.of(summary("x"))).ok()).isTrue();
        assertThat(checker.check(null, List.of(summary("x"))).ok()).isTrue();
    }
}
