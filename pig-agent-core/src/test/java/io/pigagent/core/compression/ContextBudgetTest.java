package io.pigagent.core.compression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the deterministic three-tier budget allocation ({@link BudgetRatios} normalization
 * + {@link ContextBudget#allocate}). Pure math, no model.
 */
class ContextBudgetTest {

    @Test
    void allocate_splitsDeterministicallyBySumToTotal() {
        // Arrange
        BudgetRatios ratios = new BudgetRatios(0.2, 0.3, 0.5);

        // Act
        ContextBudget b = ContextBudget.allocate(1000, ratios);

        // Assert: floors on pinned/recent, remainder to summarized, and the three tiers == total.
        assertThat(b.pinnedTokens()).isEqualTo(200);
        assertThat(b.recentTokens()).isEqualTo(300);
        assertThat(b.summarizedTokens()).isEqualTo(500);
        assertThat(b.pinnedTokens() + b.recentTokens() + b.summarizedTokens())
                .isEqualTo(b.totalTokens());
    }

    @Test
    void budgetRatios_normalizesUnnormalizedInput() {
        // Arrange: 1:1:2 should normalize to 0.25:0.25:0.5.
        BudgetRatios ratios = new BudgetRatios(1, 1, 2);

        // Assert
        assertThat(ratios.pinned()).isCloseTo(0.25, within(1e-9));
        assertThat(ratios.recent()).isCloseTo(0.25, within(1e-9));
        assertThat(ratios.summarized()).isCloseTo(0.5, within(1e-9));

        // And allocation follows the normalized split, still summing to total.
        ContextBudget b = ContextBudget.allocate(400, ratios);
        assertThat(b.pinnedTokens()).isEqualTo(100);
        assertThat(b.recentTokens()).isEqualTo(100);
        assertThat(b.summarizedTokens()).isEqualTo(200);
    }

    @Test
    void budgetRatios_nonPositiveSumFallsBackToDefaults() {
        // Arrange: all-zero (or negative) ratios must not divide-by-zero; fall back to defaults.
        BudgetRatios ratios = new BudgetRatios(0, 0, 0);

        // Assert
        assertThat(ratios.pinned()).isCloseTo(BudgetRatios.DEFAULT_PINNED, within(1e-9));
        assertThat(ratios.recent()).isCloseTo(BudgetRatios.DEFAULT_RECENT, within(1e-9));
        assertThat(ratios.summarized()).isCloseTo(BudgetRatios.DEFAULT_SUMMARIZED, within(1e-9));
    }

    @Test
    void allocate_zeroTotal_isAllZero() {
        // Act
        ContextBudget b = ContextBudget.allocate(0, BudgetRatios.defaults());

        // Assert
        assertThat(b.totalTokens()).isZero();
        assertThat(b.pinnedTokens()).isZero();
        assertThat(b.recentTokens()).isZero();
        assertThat(b.summarizedTokens()).isZero();
    }

    @Test
    void allocate_nullRatios_usesDefaults_andSumsToTotal() {
        // Act
        ContextBudget b = ContextBudget.allocate(1000, null);

        // Assert
        assertThat(b.pinnedTokens() + b.recentTokens() + b.summarizedTokens()).isEqualTo(1000);
        assertThat(b.summarizedTokens()).isEqualTo(500);
    }
}
