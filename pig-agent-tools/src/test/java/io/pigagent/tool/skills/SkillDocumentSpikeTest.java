package io.pigagent.tool.skills;

import io.pigagent.core.search.Bm25Index;
import io.pigagent.core.search.HybridRanker;
import io.pigagent.core.search.SearchDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike (tasks 组 1, 承重, capability {@code skill-matching}): prove the kernel shared-retrieval
 * primitives ({@code Bm25Index} + {@code HybridRanker} + CJK {@code Tokenizer}) can index skill
 * metadata as {@link SkillDocument} — fully decoupled from the memory/tool corpora — and rank a query
 * into a sensible order (latin + CJK). Mirrors {@code ToolDocumentSpikeTest}. This is the foundation
 * {@code skill_search} builds on.
 */
class SkillDocumentSpikeTest {

    /** A skill whose cheap metadata carries description + keywords; body access fails the test. */
    private static Skill skill(String name, String description, String... keywords) {
        return new Skill() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String content() {
                throw new AssertionError("ranking must not read the skill body: " + name);
            }

            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(name, description, List.of(keywords), "");
            }
        };
    }

    @Test
    void skillDocumentIsASearchDocument() {
        SkillDocument doc = SkillDocument.of(skill("code-review", "review code for quality", "review"));

        assertThat(doc).isInstanceOf(SearchDocument.class);
        assertThat(doc.id()).isEqualTo("code-review");
        assertThat(doc.text()).contains("code-review", "review code for quality", "review");
    }

    @Test
    void bm25IndexesSkillDocumentsAndRanksRelevantFirst() {
        // Arrange: three skill documents built from name + description + keywords, no memory/tool corpus.
        List<SkillDocument> docs = List.of(
                SkillDocument.of(skill("systematic-debugging", "Debug a crash or failing test", "debug", "crash")),
                SkillDocument.of(skill("git-commit", "Write a conventional commit message", "commit", "git")),
                SkillDocument.of(skill("security-review", "Audit code for vulnerabilities", "security", "audit")));
        Bm25Index index = new Bm25Index();
        index.index(docs);

        // Act
        Map<String, Double> scores = index.score("debug crash");

        // Assert: the debugging skill scores; the unrelated ones do not.
        assertThat(scores).containsKey("systematic-debugging");
        assertThat(scores.get("systematic-debugging")).isGreaterThan(0.0);
        assertThat(scores).doesNotContainKeys("git-commit", "security-review");
    }

    @Test
    void cjkQueryMatchesChineseSkillDescription() {
        // The corpus is often Chinese; the shared Tokenizer's CJK unigram+bigram must let a Chinese
        // query hit a Chinese skill name/description.
        List<SkillDocument> docs = List.of(
                SkillDocument.of(skill("调试排查", "系统化排查崩溃与失败", "调试")),
                SkillDocument.of(skill("代码评审", "审查代码质量与安全", "评审")));
        Bm25Index index = new Bm25Index();
        index.index(docs);

        Map<String, Double> scores = index.score("调试");

        assertThat(scores).containsKey("调试排查");
        assertThat(scores.getOrDefault("代码评审", 0.0)).isEqualTo(0.0);
    }

    @Test
    void hybridRankerBm25OnlyProducesNormalisedTopK() {
        // Route BM25 scores through HybridRanker with an empty vector map (BM25-only degradation) —
        // the same fusion/normalisation exit the memory/tool lines use — and take top-K.
        List<SkillDocument> docs = List.of(
                SkillDocument.of(skill("tdd", "write tests first, test driven development", "test", "tdd")),
                SkillDocument.of(skill("planning", "plan work, maybe a test plan", "plan")));
        Bm25Index index = new Bm25Index();
        index.index(docs);
        Map<String, Double> bm25 = index.score("test");

        List<HybridRanker.Scored> ranked = HybridRanker.rank(bm25, Map.of(), 1.0, 0.0, 0.0, 5);

        // tdd (higher term frequency, shorter doc) outranks planning.
        assertThat(ranked).extracting(HybridRanker.Scored::id).containsExactly("tdd", "planning");
        assertThat(ranked.get(0).score()).isGreaterThanOrEqualTo(ranked.get(1).score());
    }

    @Test
    void ofSkillReadsMetadataNeverBody() {
        // Progressive-loading guard: SkillDocument.of(skill) builds text from metadata() only; a skill
        // whose content() throws must not blow up (proving the body is never read for ranking).
        Skill bodyExplodes = new Skill() {
            @Override
            public String name() {
                return "refactoring";
            }

            @Override
            public String content() throws IOException {
                throw new IOException("body must not be read for ranking");
            }

            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata("refactoring", "improve code structure", List.of("refactor"), "");
            }
        };

        SkillDocument doc = SkillDocument.of(bodyExplodes);

        assertThat(doc.text()).contains("refactoring", "improve code structure", "refactor");
    }
}
