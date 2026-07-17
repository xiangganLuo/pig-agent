package io.pigagent.core.memory.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tokenizer shared by BM25 keyword scoring ({@link Bm25Index}) and the deterministic fake embedder
 * ({@link DeterministicEmbedder}), so both index the corpus consistently — capability
 * {@code hybrid-memory-search}. Latin/digit runs become lowercased word tokens; CJK runs become
 * per-character unigrams <em>plus</em> adjacent bigrams (so a Chinese query like {@code "罗湘赣"} matches
 * a document containing it — whitespace tokenization alone fails for CJK, and the corpus is often
 * Chinese). Pure, deterministic, no external dependency.
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    /** Tokenize {@code text} into keyword tokens (empty list for null/blank). */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (isLatinOrDigit(c)) {
                int start = i;
                while (i < n && isLatinOrDigit(text.charAt(i))) {
                    i++;
                }
                tokens.add(text.substring(start, i).toLowerCase(Locale.ROOT));
            } else if (isCjk(c)) {
                tokens.add(String.valueOf(c));                 // unigram
                if (i + 1 < n && isCjk(text.charAt(i + 1))) {
                    tokens.add(text.substring(i, i + 2));      // adjacent bigram
                }
                i++;
            } else {
                i++; // skip whitespace/punctuation
            }
        }
        return tokens;
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** Common CJK Unified Ideographs (covers the Chinese the memory corpus uses). */
    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
