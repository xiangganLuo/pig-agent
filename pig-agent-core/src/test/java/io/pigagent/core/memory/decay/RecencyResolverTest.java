package io.pigagent.core.memory.decay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RecencyResolver} — recency derived from the dated daily ledger (M-C, D2).
 */
class RecencyResolverTest {

    private final LocalDate today = LocalDate.of(2026, 7, 21);

    private void writeLedger(Path memoryDir, String date, String content) throws IOException {
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(date + ".md"), content, StandardCharsets.UTF_8);
    }

    @Test
    void returnsDaysSinceNewestMatchingLedger(@TempDir Path memoryDir) throws IOException {
        // Arrange: the same fact appears verbatim in an old and a recent ledger; an unrelated recent
        // ledger must not count (token-subset match is strict — a reworded line does not match).
        writeLedger(memoryDir, "2026-07-01", "user: 我在杭州工作，主要用 Java");
        writeLedger(memoryDir, "2026-07-19", "user: 顺便一提，我在杭州工作到现在");
        writeLedger(memoryDir, "2026-07-20", "assistant: 你在杭州做 Java 开发");
        RecencyResolver resolver = new RecencyResolver(memoryDir);

        // Act: recency uses the NEWEST verbatim-matching date (2026-07-19 → 2 days ago), not 07-20.
        long recency = resolver.recencyDays("我在杭州工作", today);

        // Assert
        assertThat(recency).isEqualTo(2L);
    }

    @Test
    void returnsNoSignalWhenNoLedgerMatches(@TempDir Path memoryDir) throws IOException {
        writeLedger(memoryDir, "2026-07-01", "user: 完全不相关的内容");
        RecencyResolver resolver = new RecencyResolver(memoryDir);

        assertThat(resolver.recencyDays("我叫罗湘赣", today)).isEqualTo(RecencyResolver.NO_SIGNAL);
    }

    @Test
    void returnsNoSignalForBlankOrTokenlessFact(@TempDir Path memoryDir) throws IOException {
        writeLedger(memoryDir, "2026-07-20", "anything");
        RecencyResolver resolver = new RecencyResolver(memoryDir);

        assertThat(resolver.recencyDays("   ", today)).isEqualTo(RecencyResolver.NO_SIGNAL);
        assertThat(resolver.recencyDays(null, today)).isEqualTo(RecencyResolver.NO_SIGNAL);
    }

    @Test
    void missingLedgerDirIsFaultTolerant(@TempDir Path root) {
        RecencyResolver resolver = new RecencyResolver(root.resolve("does-not-exist"));

        assertThat(resolver.recencyDays("我叫罗湘赣", today)).isEqualTo(RecencyResolver.NO_SIGNAL);
    }

    @Test
    void nonDateFilesAndDotDecayAreSkipped(@TempDir Path memoryDir) throws IOException {
        writeLedger(memoryDir, "2026-07-20", "user: 我在杭州工作");
        Files.writeString(memoryDir.resolve("NOTES.md"), "我在杭州工作", StandardCharsets.UTF_8);
        Files.createDirectories(memoryDir.resolve(".decay"));
        Files.writeString(memoryDir.resolve(".decay").resolve("access-state.json"), "{}", StandardCharsets.UTF_8);
        RecencyResolver resolver = new RecencyResolver(memoryDir);

        // Only the dated ledger 2026-07-20 counts (1 day ago), not NOTES.md or the .decay sidecar.
        assertThat(resolver.recencyDays("我在杭州工作", today)).isEqualTo(1L);
    }

    @Test
    void recencyOfTodayLedgerIsZero(@TempDir Path memoryDir) throws IOException {
        writeLedger(memoryDir, "2026-07-21", "user: 我在杭州工作");
        RecencyResolver resolver = new RecencyResolver(memoryDir);

        assertThat(resolver.recencyDays("我在杭州工作", today)).isEqualTo(0L);
    }
}
