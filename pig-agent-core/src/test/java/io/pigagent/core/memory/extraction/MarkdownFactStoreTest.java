package io.pigagent.core.memory.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** File round-trip + fault-tolerant parsing (legacy raw lines skipped, missing file → empty). */
class MarkdownFactStoreTest {

    @TempDir
    Path dir;

    private static ExtractedFact fact(String subject, FactCategory cat, String stmt, double conf, boolean corr) {
        return new ExtractedFact(subject, cat, stmt, conf, corr);
    }

    @Test
    void roundTripsFacts() {
        Path file = dir.resolve("temp-memory.md");
        MarkdownFactStore store = new MarkdownFactStore(file);
        List<ExtractedFact> facts = List.of(
                fact("language", FactCategory.USER_PREFERENCE, "Prefers Chinese", 0.95, false),
                fact("build-tool", FactCategory.PROJECT_FACT, "Uses Maven", 0.88, true));

        store.save(facts);
        List<ExtractedFact> loaded = store.load();

        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).subject()).isEqualTo("language");
        assertThat(loaded.get(0).category()).isEqualTo(FactCategory.USER_PREFERENCE);
        assertThat(loaded.get(0).statement()).isEqualTo("Prefers Chinese");
        assertThat(loaded.get(0).confidence()).isEqualTo(0.95);
        assertThat(loaded.get(1).correction()).isTrue();
    }

    @Test
    void savedFileIsHumanReadableMarkdown() throws IOException {
        Path file = dir.resolve("temp-memory.md");
        new MarkdownFactStore(file).save(List.of(
                fact("language", FactCategory.USER_PREFERENCE, "Prefers Chinese", 0.95, false)));

        String content = Files.readString(file);
        assertThat(content).contains("# Extracted memory");
        assertThat(content).contains("[user-preference] Prefers Chinese");
        assertThat(content).contains("subject=language");
    }

    @Test
    void missingFile_loadsEmpty() {
        assertThat(new MarkdownFactStore(dir.resolve("nope.md")).load()).isEmpty();
    }

    @Test
    void skipsLegacyRawLines() throws IOException {
        Path file = dir.resolve("temp-memory.md");
        Files.writeString(file, """
                ## 2026-07-15 10:00
                - some old raw entry without metadata
                - [project-fact] Uses Maven <!-- subject=build-tool; conf=0.9; correction=false -->
                """);
        List<ExtractedFact> loaded = new MarkdownFactStore(file).load();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).subject()).isEqualTo("build-tool");
    }

    @Test
    void saveNull_isNoOp() {
        Path file = dir.resolve("temp-memory.md");
        new MarkdownFactStore(file).save(null);
        assertThat(Files.exists(file)).isFalse();
    }
}
