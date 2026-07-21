package io.pigagent.core.memory.quality.eval;

import java.util.List;

/**
 * A fixed-conversation memory-quality evaluation fixture — capability
 * {@code memory-consolidation-quality}. It drives the memory pipeline (flush → consolidation → dedup)
 * with a deterministic conversation and declares the quality expectations to assert against the
 * resulting {@code MEMORY.md}: which key facts MUST be remembered, and the similarity threshold under
 * which no near-duplicates should remain.
 *
 * <p>Test-support (offline structure is driven by a fake extractor); the real-model quality baseline is
 * a deferred live {@code *IT}. Deliberately generic so a future layered-decay capability (M-C) can reuse
 * the same fixtures + {@link MemoryQualityAssertions}.
 *
 * @param name             a short fixture label
 * @param conversation     the fixed conversation turns (user/assistant transcript lines)
 * @param expectedKeyFacts facts that MUST survive into {@code MEMORY.md}
 * @param dedupThreshold   the similarity threshold at/above which two facts count as near-duplicates
 */
public record MemoryEvalFixture(String name, List<String> conversation, List<String> expectedKeyFacts,
                                double dedupThreshold) {

    public MemoryEvalFixture {
        conversation = List.copyOf(conversation);
        expectedKeyFacts = List.copyOf(expectedKeyFacts);
    }

    /** A Chinese personal-facts fixture whose ledger contains a near-duplicate of one key fact. */
    public static MemoryEvalFixture personalFactsWithDuplicate() {
        return new MemoryEvalFixture(
                "personal-facts-with-duplicate",
                List.of(
                        "user: 我叫罗湘赣，请牢牢记住我的名字",
                        "assistant: 好的，罗湘赣，我记住了",
                        "user: 我在杭州工作，主要用 Java",
                        "assistant: 明白，你在杭州做 Java 开发"),
                List.of("我叫罗湘赣", "我在杭州工作", "主要用 Java"),
                0.9);
    }
}
