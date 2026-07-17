package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** In-memory brute-force cosine store: ordering, top-K, empty inputs, clear. */
class InMemoryVectorStoreTest {

    @Test
    void ordersByCosineDescending() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert("a", new float[]{1f, 0f, 0f});
        store.upsert("b", new float[]{0f, 1f, 0f});
        store.upsert("c", new float[]{0.9f, 0.1f, 0f}); // close to the query

        List<VectorStore.Scored> hits = store.search(new float[]{1f, 0f, 0f}, 3);

        assertThat(hits).extracting(VectorStore.Scored::id).containsExactly("a", "c", "b");
    }

    @Test
    void topKLimitsResults() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert("a", new float[]{1f, 0f});
        store.upsert("b", new float[]{0f, 1f});
        assertThat(store.search(new float[]{1f, 0f}, 1)).hasSize(1);
    }

    @Test
    void emptyStoreOrEmptyQueryReturnsEmpty() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        assertThat(store.search(new float[]{1f, 0f}, 5)).isEmpty();
        store.upsert("a", new float[]{1f, 0f});
        assertThat(store.search(new float[]{}, 5)).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.upsert("a", new float[]{1f, 0f});
        store.clear();
        assertThat(store.size()).isZero();
    }
}
