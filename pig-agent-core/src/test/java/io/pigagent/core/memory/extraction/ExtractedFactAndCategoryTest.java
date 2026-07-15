package io.pigagent.core.memory.extraction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Value-object invariants: category fault-tolerant parsing + fact confidence clamping/immutability. */
class ExtractedFactAndCategoryTest {

    @Test
    void category_fromLabel_parsesKnownLabels() {
        assertThat(FactCategory.fromLabel("user-preference", FactCategory.REFERENCE))
                .isEqualTo(FactCategory.USER_PREFERENCE);
        assertThat(FactCategory.fromLabel("project-fact", FactCategory.REFERENCE))
                .isEqualTo(FactCategory.PROJECT_FACT);
        // tolerant of underscores / case
        assertThat(FactCategory.fromLabel("User_Preference", FactCategory.REFERENCE))
                .isEqualTo(FactCategory.USER_PREFERENCE);
    }

    @Test
    void category_fromLabel_unknownOrNull_fallsBack() {
        assertThat(FactCategory.fromLabel("nonsense", FactCategory.PROJECT_FACT))
                .isEqualTo(FactCategory.PROJECT_FACT);
        assertThat(FactCategory.fromLabel(null, FactCategory.REFERENCE))
                .isEqualTo(FactCategory.REFERENCE);
        assertThat(FactCategory.fromLabel("  ", FactCategory.REFERENCE))
                .isEqualTo(FactCategory.REFERENCE);
    }

    @Test
    void fact_clampsConfidenceToUnitRange() {
        assertThat(new ExtractedFact("s", FactCategory.REFERENCE, "x", 1.5, false).confidence())
                .isEqualTo(1.0);
        assertThat(new ExtractedFact("s", FactCategory.REFERENCE, "x", -0.3, false).confidence())
                .isEqualTo(0.0);
        assertThat(new ExtractedFact("s", FactCategory.REFERENCE, "x", 0.42, false).confidence())
                .isEqualTo(0.42);
    }

    @Test
    void fact_nullFieldsBecomeSafeDefaults() {
        ExtractedFact f = new ExtractedFact(null, null, null, 0.5, false);
        assertThat(f.subject()).isEmpty();
        assertThat(f.statement()).isEmpty();
        assertThat(f.category()).isEqualTo(FactCategory.PROJECT_FACT);
        assertThat(f.isBlank()).isTrue();
    }

    @Test
    void asCorrection_marksHighConfidenceAndCorrectionFlag() {
        ExtractedFact f = new ExtractedFact("language", FactCategory.USER_PREFERENCE, "Chinese", 0.2, false);
        ExtractedFact c = f.asCorrection();
        assertThat(c.correction()).isTrue();
        assertThat(c.confidence()).isEqualTo(ExtractedFact.CORRECTION_CONFIDENCE);
        // original unchanged (immutability)
        assertThat(f.correction()).isFalse();
        assertThat(f.confidence()).isEqualTo(0.2);
    }

    @Test
    void subjectKey_isCaseInsensitive() {
        assertThat(new ExtractedFact("Language", FactCategory.REFERENCE, "x", 1, false).subjectKey())
                .isEqualTo("language");
    }
}
