package io.pigagent.core.memory.search;

import io.pigagent.core.search.SearchDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryDocument now implements the shared {@link SearchDocument} contract, so the memory corpus can be
 * indexed by the same shared retrieval primitives. Verifies the interface view exposes {@code id()} /
 * {@code text()} exactly as the record accessors, while the memory-specific {@code sourceLabel} stays
 * off the shared contract.
 */
class MemoryDocumentTest {

    @Test
    void isASearchDocumentExposingIdAndText() {
        MemoryDocument doc = new MemoryDocument("MEMORY.md#0", "MEMORY.md", "the user prefers dark mode");

        assertThat(doc).isInstanceOf(SearchDocument.class);
        SearchDocument view = doc;
        assertThat(view.id()).isEqualTo("MEMORY.md#0");
        assertThat(view.text()).isEqualTo("the user prefers dark mode");
        assertThat(doc.sourceLabel()).isEqualTo("MEMORY.md"); // domain field stays on the record
    }
}
