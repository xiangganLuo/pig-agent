package io.pigagent.core.compression;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link ContextEngineer} orchestration: defaults reproduce prior behavior,
 * high-importance and protected content is kept verbatim, and a consistency failure falls back to
 * the safer profile (preserving critical facts) instead of dropping them. Summarizer is a mock.
 */
class ContextEngineerTest {

    private static final int BUDGET = 1000;
    private static final CompressionService.Summarizer CONST = older -> "SUMMARY";

    private static Msg user(String t) {
        return Msg.builder().name("u").role(MsgRole.USER).content(TextBlock.builder().text(t).build()).build();
    }

    /** decision at index 0, three fillers, then six recents → size 10, splitAt 4 (keepRecent 6). */
    private static List<Msg> withLeading(Msg leading) {
        List<Msg> msgs = new ArrayList<>();
        msgs.add(leading);
        msgs.add(user("filler 0"));
        msgs.add(user("filler 1"));
        msgs.add(user("filler 2"));
        for (int i = 0; i < 6; i++) {
            msgs.add(user("recent " + i));
        }
        return msgs;
    }

    private static boolean anyContains(List<Msg> msgs, String needle) {
        return msgs.stream().anyMatch(m -> {
            String t = m.getTextContent();
            return t != null && t.contains(needle);
        });
    }

    @Test
    void defaultsReproducePriorBehavior_summaryPlusRecent() {
        // Arrange: 10 plain messages (nothing high-importance or protected).
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(user("message " + i));
        }
        ContextEngineer engineer = ContextEngineer.withDefaults(CONST, EngineeringOptions.defaults());

        // Act
        List<Msg> plan = engineer.rewrite(msgs, BUDGET);

        // Assert: one summary + the 6 most recent verbatim — identical to prior behavior.
        assertThat(plan).hasSize(7);
        assertThat(plan.get(0).getTextContent()).contains("SUMMARY");
        assertThat(plan.subList(1, 7)).containsExactlyElementsOf(msgs.subList(4, 10));
    }

    @Test
    void highImportanceOlderTurnKeptVerbatim() {
        // Arrange: a user decision is the oldest turn.
        Msg decision = user("We must use Postgres instead of MySQL; do not change this.");
        List<Msg> msgs = withLeading(decision);
        ContextEngineer engineer = ContextEngineer.withDefaults(CONST, EngineeringOptions.defaults());

        // Act
        List<Msg> plan = engineer.rewrite(msgs, BUDGET);

        // Assert: summary + decision (verbatim) + 6 recents; the decision text survived.
        assertThat(plan).hasSize(8);
        assertThat(plan).contains(decision);
        assertThat(anyContains(plan, "Postgres")).isTrue();
    }

    @Test
    void protectedCodeNotSummarized() {
        // Arrange: the oldest turn carries a fenced code block.
        Msg code = user("Apply this fix:\n```java\nint x = compute();\n```\nthen rerun.");
        List<Msg> msgs = withLeading(code);
        ContextEngineer engineer = ContextEngineer.withDefaults(CONST, EngineeringOptions.defaults());

        // Act
        List<Msg> plan = engineer.rewrite(msgs, BUDGET);

        // Assert: the protected message is kept verbatim (code fence survives, not summarized away).
        assertThat(plan).contains(code);
        assertThat(anyContains(plan, "int x = compute();")).isTrue();
    }

    @Test
    void consistencyFailureFallsBackToSafe_preservesFact() {
        // Arrange: aggressive profile (importance + verbatim OFF) would summarize the decision away,
        // but the consistency check demands it survive → safe fallback restores it verbatim.
        Msg decision = user("We must use Postgres instead of MySQL; do not change this.");
        List<Msg> msgs = withLeading(decision);
        EngineeringOptions aggressive = new EngineeringOptions(
                true, 3, /*importance*/ false, /*verbatim*/ false, /*consistency*/ true,
                6, BudgetRatios.defaults());
        ContextEngineer engineer = ContextEngineer.withDefaults(CONST, aggressive);

        // Act
        List<Msg> plan = engineer.rewrite(msgs, BUDGET);

        // Assert: fallback preserved the decision verbatim (fact not silently dropped).
        assertThat(plan).isNotNull();
        assertThat(anyContains(plan, "Postgres")).isTrue();
        assertThat(plan).contains(decision);
    }

    @Test
    void consistencyDisabled_returnsAggressiveCandidate() {
        // Arrange: same aggressive profile but consistency OFF → the fact is summarized away and NOT
        // rescued (proves the toggle gates the safety net).
        Msg decision = user("We must use Postgres instead of MySQL; do not change this.");
        List<Msg> msgs = withLeading(decision);
        EngineeringOptions noCheck = new EngineeringOptions(
                true, 3, false, false, /*consistency*/ false, 6, BudgetRatios.defaults());
        ContextEngineer engineer = ContextEngineer.withDefaults(CONST, noCheck);

        // Act
        List<Msg> plan = engineer.rewrite(msgs, BUDGET);

        // Assert: summary + 6 recents (decision folded into the summary, not kept verbatim).
        assertThat(plan).hasSize(7);
        assertThat(anyContains(plan, "Postgres")).isFalse();
    }

    @Test
    void blankSummaryKeepsOriginal() {
        // Arrange: summarizer returns blank → keep original context (null plan).
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(user("message " + i));
        }
        ContextEngineer engineer = ContextEngineer.withDefaults(older -> "", EngineeringOptions.defaults());

        // Act / Assert
        assertThat(engineer.rewrite(msgs, BUDGET)).isNull();
    }

    @Test
    void nothingToCompressWhenSmall() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            msgs.add(user("m " + i)); // <= keepRecent (6)
        }
        ContextEngineer engineer = ContextEngineer.withDefaults(CONST, EngineeringOptions.defaults());
        assertThat(engineer.rewrite(msgs, BUDGET)).isNull();
    }
}
