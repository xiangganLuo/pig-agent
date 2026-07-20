package io.pigagent.tool.deferred;

import io.pigagent.core.search.Bm25Index;
import io.pigagent.core.search.HybridRanker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike (tasks 组 1, 承重): prove the kernel shared-retrieval primitives ({@code Bm25Index} +
 * {@code HybridRanker} + CJK {@code Tokenizer}) can index tool metadata as {@link ToolDocument}
 * — fully decoupled from the memory corpus — and rank a query into a sensible order (latin + CJK).
 * This is the foundation the {@code tool_search} hybrid ranking builds on.
 */
class ToolDocumentSpikeTest {

    private static ToolDocument doc(String name, String desc, String... kw) {
        return ToolDocument.of(new DeferredTool(name, desc, Set.of(kw), "g"));
    }

    @Test
    void bm25IndexesToolDocumentsAndRanksRelevantFirst() {
        // Arrange: three tool documents built from name + description + keywords, no memory corpus.
        List<ToolDocument> docs = List.of(
                doc("getWeather", "Get the current weather forecast for a city", "weather", "forecast"),
                doc("sendEmail", "Send an email message to a recipient", "email", "message"),
                doc("queryDatabase", "Run a SQL query against the database", "database", "sql"));
        Bm25Index index = new Bm25Index();
        index.index(docs);

        // Act
        Map<String, Double> scores = index.score("weather forecast");

        // Assert: the weather tool scores; the unrelated ones do not.
        assertThat(scores).containsKey("getWeather");
        assertThat(scores.get("getWeather")).isGreaterThan(0.0);
        assertThat(scores).doesNotContainKeys("sendEmail", "queryDatabase");
    }

    @Test
    void keywordsExposeCamelCaseSplitsForRecall() {
        // The raw name "webSearch" tokenizes as one latin run; the keywords carry the "search" split,
        // so a bare "search" query still matches via the document text.
        List<ToolDocument> docs = List.of(
                doc("webSearch", "Search the public web", "web", "search"),
                doc("readFile", "Read a file from disk", "read", "file"));
        Bm25Index index = new Bm25Index();
        index.index(docs);

        Map<String, Double> scores = index.score("search");

        assertThat(scores).containsKey("webSearch");
        assertThat(scores).doesNotContainKey("readFile");
    }

    @Test
    void cjkQueryMatchesChineseDescription() {
        // The corpus is often Chinese; the shared Tokenizer's CJK unigram+bigram must let a Chinese
        // query hit a Chinese tool description.
        List<ToolDocument> docs = List.of(
                doc("chaTianQi", "查询城市天气预报", "天气"),
                doc("faYouJian", "发送电子邮件", "邮件"));
        Bm25Index index = new Bm25Index();
        index.index(docs);

        Map<String, Double> scores = index.score("天气");

        assertThat(scores).containsKey("chaTianQi");
        assertThat(scores.getOrDefault("faYouJian", 0.0)).isEqualTo(0.0);
    }

    @Test
    void hybridRankerBm25OnlyProducesNormalisedTopK() {
        // Route BM25 scores through HybridRanker with an empty vector map (BM25-only degradation) —
        // the same fusion/normalisation exit the memory line uses — and take top-K.
        List<ToolDocument> docs = List.of(
                doc("sendEmail", "send an email", "email", "send"),
                doc("logEvent", "log an event, maybe about email", "log", "event"));
        Bm25Index index = new Bm25Index();
        index.index(docs);
        Map<String, Double> bm25 = index.score("email");

        List<HybridRanker.Scored> ranked = HybridRanker.rank(bm25, Map.of(), 1.0, 0.0, 0.0, 5);

        // sendEmail (higher term frequency, shorter doc) outranks logEvent.
        assertThat(ranked).extracting(HybridRanker.Scored::id).containsExactly("sendEmail", "logEvent");
        assertThat(ranked.get(0).score()).isGreaterThanOrEqualTo(ranked.get(1).score());
    }
}
