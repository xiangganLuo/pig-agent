package io.pigagent.core.memory.decay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DecayArchiveWriter} — the dot-prefixed audit archive ledger (M-C, D5).
 */
class DecayArchiveWriterTest {

    private final LocalDate today = LocalDate.of(2026, 7, 21);

    @Test
    void appendsFactsToMonthlyDotDecayArchive(@TempDir Path memoryDir) throws IOException {
        // Arrange
        DecayArchiveWriter writer = new DecayArchiveWriter(memoryDir);

        // Act
        boolean ok = writer.archive(List.of("- 昨天临时开的端口", "- 一次性的调试笔记"), today);

        // Assert: written under memory/.decay/archive/2026-07.md (dot-prefixed → not native-ingested).
        assertThat(ok).isTrue();
        Path archive = memoryDir.resolve(".decay").resolve("archive").resolve("2026-07.md");
        assertThat(Files.isRegularFile(archive)).isTrue();
        String body = Files.readString(archive, StandardCharsets.UTF_8);
        assertThat(body).contains("昨天临时开的端口").contains("一次性的调试笔记").contains("[2026-07-21]");
    }

    @Test
    void appendsAcrossCallsWithinSameMonth(@TempDir Path memoryDir) throws IOException {
        DecayArchiveWriter writer = new DecayArchiveWriter(memoryDir);

        writer.archive(List.of("- fact one"), today);
        writer.archive(List.of("- fact two"), today);

        String body = Files.readString(writer.archiveFileFor(today), StandardCharsets.UTF_8);
        assertThat(body).contains("fact one").contains("fact two");
    }

    @Test
    void emptyListIsANoOpAndReturnsTrue(@TempDir Path memoryDir) {
        DecayArchiveWriter writer = new DecayArchiveWriter(memoryDir);

        assertThat(writer.archive(List.of(), today)).isTrue();
        assertThat(Files.exists(memoryDir.resolve(".decay"))).isFalse();
    }
}
