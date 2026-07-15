package io.pigagent.core.memory.extraction;

import java.util.Objects;

/**
 * An immutable, classified memory fact extracted from a conversation turn.
 *
 * <ul>
 *   <li>{@code subject} — the canonical key used for dedup/supersede (e.g. {@code "language"},
 *       {@code "build-tool"}). Two facts with the same subject describe the same thing; the newer
 *       one supersedes the older (see {@link FactMerger}).</li>
 *   <li>{@code category} — {@link FactCategory} bucket.</li>
 *   <li>{@code statement} — a short, canonical natural-language statement of the fact.</li>
 *   <li>{@code confidence} — model confidence in [0,1]; the compact constructor clamps out-of-range
 *       values (so a stray {@code 1.5}/{@code -0.3} can never poison the confidence gate).</li>
 *   <li>{@code correction} — true when the user is correcting a prior fact; such facts are treated
 *       as high confidence and always supersede the stale fact by subject.</li>
 * </ul>
 */
public record ExtractedFact(String subject, FactCategory category, String statement,
                            double confidence, boolean correction) {

    /** Confidence assigned to a correction (bypasses the confidence gate — always kept). */
    public static final double CORRECTION_CONFIDENCE = 1.0;

    public ExtractedFact {
        subject = subject == null ? "" : subject.strip();
        category = category == null ? FactCategory.PROJECT_FACT : category;
        statement = statement == null ? "" : statement.strip();
        confidence = clamp(confidence);
    }

    private static double clamp(double c) {
        if (Double.isNaN(c)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, c));
    }

    /** True when this fact carries no usable content (empty subject or statement). */
    public boolean isBlank() {
        return subject.isEmpty() || statement.isEmpty();
    }

    /** A copy with the given confidence (clamped by the constructor). */
    public ExtractedFact withConfidence(double newConfidence) {
        return new ExtractedFact(subject, category, statement, newConfidence, correction);
    }

    /** A copy flagged as a correction and normalized to high confidence. */
    public ExtractedFact asCorrection() {
        return new ExtractedFact(subject, category, statement, CORRECTION_CONFIDENCE, true);
    }

    /** Case-insensitive subject key for dedup (so {@code Language} and {@code language} collapse). */
    public String subjectKey() {
        return subject.toLowerCase();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExtractedFact f)) {
            return false;
        }
        return Double.compare(confidence, f.confidence) == 0
                && correction == f.correction
                && subject.equals(f.subject)
                && category == f.category
                && statement.equals(f.statement);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, category, statement, confidence, correction);
    }
}
