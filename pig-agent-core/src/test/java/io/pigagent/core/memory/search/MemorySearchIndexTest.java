package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end index behavior (offline): indexes MEMORY.md + memory/*.md + USER.md, BM25-only vs hybrid
 * (fake embedder) blending, top-K, blank query, throttled rebuild on file change, and graceful
 * degradation when the embedder fails.
 */
class MemorySearchIndexTest {

    @TempDir
    Path root;

    private MemorySearchIndex buildIndex(Embedder embedder) throws IOException {
        Files.writeString(root.resolve("MEMORY.md"), "# Memory\n\n- The user prefers dark mode themes\n");
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("memory").resolve("2026-07-17.md"),
                "The deploy pipeline runs nightly at 2am\n");
        Files.writeString(root.resolve("USER.md"), "# User Profile\n\n- **name**: 罗湘赣\n");
        return newIndex(embedder, MemorySearchConfig.defaults());
    }

    private MemorySearchIndex newIndex(Embedder embedder, MemorySearchConfig cfg) {
        MemoryCorpusLoader loader = new MemoryCorpusLoader(
                root.resolve("MEMORY.md"), root.resolve("memory"), root.resolve("USER.md"));
        return new MemorySearchIndex(loader, new InMemoryVectorStore(), embedder, cfg);
    }

    @Test
    void indexesAllThreeCorpusFiles() throws IOException {
        MemorySearchIndex idx = buildIndex(null);
        assertThat(idx.search("dark mode", 10)).isNotEmpty();
        assertThat(idx.search("deploy nightly", 10)).isNotEmpty();
        assertThat(idx.search("罗湘赣", 10))
                .anySatisfy(d -> assertThat(d.sourceLabel()).isEqualTo("USER.md"));
    }

    @Test
    void bm25OnlyWhenNoEmbedder() throws IOException {
        MemorySearchIndex idx = buildIndex(null);
        assertThat(idx.vectorEnabled()).isFalse();
        List<MemoryDocument> hits = idx.search("dark mode themes", 5);
        assertThat(hits.get(0).text()).contains("dark mode");
    }

    @Test
    void hybridSearchWorksWithFakeEmbedder() throws IOException {
        MemorySearchIndex idx = buildIndex(new DeterministicEmbedder());
        assertThat(idx.vectorEnabled()).isTrue();
        assertThat(idx.search("dark mode", 5)).isNotEmpty();
    }

    @Test
    void blankQueryReturnsEmpty() throws IOException {
        assertThat(buildIndex(null).search("   ", 5)).isEmpty();
    }

    @Test
    void respectsTopK() throws IOException {
        StringBuilder sb = new StringBuilder("# Memory\n");
        for (int i = 0; i < 10; i++) {
            sb.append("\n## Section ").append(i).append("\na note about apples number ").append(i).append('\n');
        }
        Files.writeString(root.resolve("MEMORY.md"), sb.toString());
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("USER.md"), "# U\n- x\n");
        MemorySearchIndex idx = newIndex(null, MemorySearchConfig.defaults());
        assertThat(idx.search("apples", 3)).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void rebuildsWhenFileChangesAfterThrottle() throws IOException {
        Files.writeString(root.resolve("MEMORY.md"), "# M\n\n- alpha content here\n");
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("USER.md"), "# U\n- x\n");
        MemorySearchConfig noThrottle = new MemorySearchConfig(0.7, 0.3, 4, 0.0, 8, 0L);
        MemorySearchIndex idx = newIndex(null, noThrottle);

        assertThat(idx.search("alpha", 5)).isNotEmpty();
        assertThat(idx.search("beta", 5)).isEmpty();

        Files.writeString(root.resolve("MEMORY.md"), "# M\n\n- beta content now\n");
        Files.setLastModifiedTime(root.resolve("MEMORY.md"),
                FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        assertThat(idx.search("beta", 5)).isNotEmpty(); // picked up the change
    }

    @Test
    void embedderFailureDegradesToBm25() throws IOException {
        Embedder boom = text -> {
            throw new RuntimeException("no network");
        };
        MemorySearchIndex idx = buildIndex(boom);
        assertThat(idx.search("dark mode", 5)).isNotEmpty(); // still works via BM25
    }
}
