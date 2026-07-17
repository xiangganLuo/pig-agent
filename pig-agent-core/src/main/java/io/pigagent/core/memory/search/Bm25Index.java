package io.pigagent.core.memory.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java Okapi BM25 keyword scorer over the memory corpus — capability {@code hybrid-memory-search}.
 * Deterministic, offline, no external dependency (the reliable, offline-provable half of the hybrid
 * rank; the vector half sits behind the {@link Embedder} seam). Standard parameters {@code k1=1.2} /
 * {@code b=0.75}; IDF uses the always-positive BM25+ form so a common term never subtracts.
 *
 * <p>Not thread-safe on its own — the owning {@link MemorySearchIndex} guards {@code index}/{@code
 * score} under a lock.
 */
public final class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private List<String> docIds = List.of();
    private List<Map<String, Integer>> termFreqs = List.of();
    private double[] docLengths = new double[0];
    private double avgDocLength = 0.0;
    private Map<String, Integer> docFreq = new HashMap<>();
    private int docCount = 0;

    /** (Re)build the index from the corpus. */
    public void index(List<MemoryDocument> docs) {
        docIds = new java.util.ArrayList<>(docs.size());
        termFreqs = new java.util.ArrayList<>(docs.size());
        docLengths = new double[docs.size()];
        docFreq = new HashMap<>();
        long totalLen = 0;
        for (int d = 0; d < docs.size(); d++) {
            MemoryDocument doc = docs.get(d);
            List<String> tokens = Tokenizer.tokenize(doc.text());
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            docIds.add(doc.id());
            termFreqs.add(tf);
            docLengths[d] = tokens.size();
            totalLen += tokens.size();
            for (String term : tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }
        docCount = docs.size();
        avgDocLength = docCount == 0 ? 0.0 : (double) totalLen / docCount;
    }

    /**
     * Score every document against {@code query} (returns only documents with a positive score, keyed
     * by document id). Empty when the index is empty or the query has no in-vocabulary terms.
     */
    public Map<String, Double> score(String query) {
        Map<String, Double> scores = new HashMap<>();
        if (docCount == 0 || avgDocLength == 0.0) {
            return scores;
        }
        List<String> queryTerms = Tokenizer.tokenize(query);
        for (int d = 0; d < docCount; d++) {
            Map<String, Integer> tf = termFreqs.get(d);
            double dl = docLengths[d];
            double score = 0.0;
            for (String term : queryTerms) {
                Integer f = tf.get(term);
                if (f == null || f == 0) {
                    continue;
                }
                double idf = idf(term);
                double denom = f + K1 * (1 - B + B * dl / avgDocLength);
                score += idf * (f * (K1 + 1)) / denom;
            }
            if (score > 0.0) {
                scores.put(docIds.get(d), score);
            }
        }
        return scores;
    }

    /** BM25+ IDF: {@code ln(1 + (N - df + 0.5) / (df + 0.5))} — always &gt; 0. */
    private double idf(String term) {
        int df = docFreq.getOrDefault(term, 0);
        return Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
    }
}
