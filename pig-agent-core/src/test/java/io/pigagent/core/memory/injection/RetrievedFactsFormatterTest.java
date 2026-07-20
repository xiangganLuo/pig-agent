package io.pigagent.core.memory.injection;

import io.pigagent.core.memory.search.MemoryDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic coverage for {@link RetrievedFactsFormatter} (capability {@code memory-retrieval-injection}):
 * bounded bullet formatting, empty-input degradation, and dedup against the pinned block.
 */
class RetrievedFactsFormatterTest {

    private static MemoryDocument doc(String id, String text) {
        return new MemoryDocument(id, "MEMORY.md", text);
    }

    @Test
    void format_rendersHeaderAndBullets() {
        String out = RetrievedFactsFormatter.format(List.of(
                doc("a", "The user prefers dark mode"),
                doc("b", "The project uses Java 17")));

        assertThat(out).contains(RetrievedFactsFormatter.HEADER);
        assertThat(out).contains("- The user prefers dark mode");
        assertThat(out).contains("- The project uses Java 17");
    }

    @Test
    void format_emptyList_returnsEmpty() {
        assertThat(RetrievedFactsFormatter.format(List.of())).isEmpty();
        assertThat(RetrievedFactsFormatter.format(null)).isEmpty();
    }

    @Test
    void format_onlyBlankDocs_returnsEmpty() {
        assertThat(RetrievedFactsFormatter.format(List.of(doc("a", "   ")))).isEmpty();
    }

    @Test
    void dedup_dropsFactsAlreadyPinned() {
        List<MemoryDocument> hits = List.of(
                doc("a", "User's name is 罗湘赣"),
                doc("b", "The user prefers dark mode"));
        String pinned = "## Pinned\n- User's name is 罗湘赣\n- Always reply in Chinese";

        List<MemoryDocument> kept = RetrievedFactsFormatter.dedupAgainstPinned(hits, pinned);

        assertThat(kept).extracting(MemoryDocument::id).containsExactly("b");
    }

    @Test
    void dedup_emptyPinned_keepsAll() {
        List<MemoryDocument> hits = List.of(doc("a", "fact one"), doc("b", "fact two"));

        assertThat(RetrievedFactsFormatter.dedupAgainstPinned(hits, "")).hasSize(2);
        assertThat(RetrievedFactsFormatter.dedupAgainstPinned(hits, null)).hasSize(2);
    }
}
