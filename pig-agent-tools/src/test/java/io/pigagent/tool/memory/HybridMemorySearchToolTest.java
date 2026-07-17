package io.pigagent.tool.memory;

import io.pigagent.core.memory.search.InMemoryVectorStore;
import io.pigagent.core.memory.search.MemoryCorpusLoader;
import io.pigagent.core.memory.search.MemorySearchConfig;
import io.pigagent.core.memory.search.MemorySearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The pig hybrid {@code memory_search} tool: matching snippets, no-match line, {@code {"error"}} contract. */
class HybridMemorySearchToolTest {

    @TempDir
    Path root;

    private MemorySearchIndex realIndex() throws IOException {
        Files.writeString(root.resolve("MEMORY.md"), "# Memory\n\n- the user likes dark mode\n");
        Files.createDirectories(root.resolve("memory"));
        Files.writeString(root.resolve("USER.md"), "# U\n- **name**: Alice\n");
        MemoryCorpusLoader loader = new MemoryCorpusLoader(
                root.resolve("MEMORY.md"), root.resolve("memory"), root.resolve("USER.md"));
        return new MemorySearchIndex(loader, new InMemoryVectorStore(), null, MemorySearchConfig.defaults());
    }

    @Test
    void returnsMatchingSnippetsWithSourceLabel() throws IOException {
        HybridMemorySearchTool tool = new HybridMemorySearchTool(realIndex(), 5);
        String out = tool.memorySearch("dark mode");
        assertThat(out).contains("dark mode").contains("MEMORY.md");
    }

    @Test
    void blankQueryReturnsCanonicalError() throws IOException {
        HybridMemorySearchTool tool = new HybridMemorySearchTool(realIndex(), 5);
        assertThat(tool.memorySearch("   ")).contains("\"error\"");
    }

    @Test
    void noMatchReturnsFriendlyLine() throws IOException {
        HybridMemorySearchTool tool = new HybridMemorySearchTool(realIndex(), 5);
        assertThat(tool.memorySearch("zzznonexistentterm")).contains("No memory matched");
    }

    @Test
    void indexFailureReturnsCanonicalErrorNotThrow() {
        MemorySearchIndex failing = mock(MemorySearchIndex.class);
        when(failing.search(anyString(), anyInt())).thenThrow(new RuntimeException("boom"));
        HybridMemorySearchTool tool = new HybridMemorySearchTool(failing, 5);
        assertThat(tool.memorySearch("query")).contains("\"error\"");
    }
}
