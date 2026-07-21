package io.pigagent.core.memory.decay;

import io.pigagent.core.search.Tokenizer;

import java.util.List;
import java.util.TreeSet;

/**
 * A normalized, order-independent fingerprint of a memory fact — capability
 * {@code memory-layering-and-decay} (M-C, D2). Used as the stable key for the access/reuse sidecar
 * ({@link MemoryAccessStore}) so a fact's reuse history survives light rewording: the fingerprint is the
 * fact's <b>sorted distinct token set</b> (via the shared CJK-aware {@link Tokenizer}), joined by
 * {@code '|'}. Two facts that differ only in word order / punctuation / casing share a fingerprint;
 * heavy rewording resets history (a documented honest limit — it only loses the reuse bonus, never
 * deletes a fact). Pure, deterministic; blank/tokenless input → the empty fingerprint {@code ""}.
 */
public final class FactFingerprint {

    private FactFingerprint() {
    }

    /** The order-independent token-set fingerprint of {@code text} (empty string when no tokens). */
    public static String of(String text) {
        List<String> tokens = Tokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return "";
        }
        TreeSet<String> distinctSorted = new TreeSet<>(tokens);
        return String.join("|", distinctSorted);
    }
}
