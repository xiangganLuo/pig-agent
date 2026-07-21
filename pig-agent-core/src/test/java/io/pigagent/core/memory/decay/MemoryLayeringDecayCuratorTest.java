package io.pigagent.core.memory.decay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryLayeringDecayCurator} — the post-consolidation layering/decay curator
 * (M-C, D4). Real {@code @TempDir} IO; a fixed clock pins {@code today} = 2026-07-21 for deterministic
 * recency. Scorer thresholds: stale=30, archive=90, reinforce=2, promote=5.
 */
class MemoryLayeringDecayCuratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);
    private final Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private MemoryLayeringDecayCurator curator(Path memoryMd, Path memoryDir, boolean autoArchive) {
        return new MemoryLayeringDecayCurator(memoryMd, memoryDir, new FactLayerClassifier(),
                new RetentionScorer(30, 90, 2, 5), new DecayArchiveWriter(memoryDir),
                autoArchive, Duration.ZERO, clock);
    }

    private void ledger(Path memoryDir, String date, String content) throws IOException {
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(date + ".md"), content, StandardCharsets.UTF_8);
    }

    @Test
    void dryRunLeavesMemoryFileByteIdentical(@TempDir Path root) throws IOException {
        // Arrange: a stale volatile fact that WOULD archive.
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        String original = "## Recent\n- 昨天临时开了一个测试端口";
        Files.writeString(memoryMd, original, StandardCharsets.UTF_8);
        ledger(memoryDir, "2026-01-01", "- 昨天临时开了一个测试端口"); // recency ~201d > archive 90

        // Act: default dry-run.
        boolean rewritten = curator(memoryMd, memoryDir, false).curateNow();

        // Assert: nothing changed, no archive written.
        assertThat(rewritten).isFalse();
        assertThat(Files.readString(memoryMd, StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(Files.exists(memoryDir.resolve(".decay").resolve("archive"))).isFalse();
    }

    @Test
    void autoArchiveArchivesStaleVolatileAndKeepsFreshGeneral(@TempDir Path root) throws IOException {
        // Arrange
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        Files.writeString(memoryMd,
                "## Recent\n- 昨天临时开了一个测试端口\n- 我在杭州工作，主要用 Java",
                StandardCharsets.UTF_8);
        ledger(memoryDir, "2026-01-01", "- 昨天临时开了一个测试端口");          // stale volatile → archive
        ledger(memoryDir, "2026-07-20", "- 我在杭州工作，主要用 Java");         // fresh general → keep

        // Act
        boolean rewritten = curator(memoryMd, memoryDir, true).curateNow();

        // Assert: volatile archived + removed, general kept under ## General.
        assertThat(rewritten).isTrue();
        String result = Files.readString(memoryMd, StandardCharsets.UTF_8);
        assertThat(result).doesNotContain("昨天临时开了一个测试端口");
        assertThat(result).contains("## General").contains("我在杭州工作");
        String archive = Files.readString(
                memoryDir.resolve(".decay").resolve("archive").resolve("2026-07.md"), StandardCharsets.UTF_8);
        assertThat(archive).contains("昨天临时开了一个测试端口");
    }

    @Test
    void reLayersFlatLegacyFileByContent(@TempDir Path root) throws IOException {
        // Arrange: a flat (unmarked) legacy MEMORY.md — layers must be re-derived from content.
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        Files.writeString(memoryMd, "- 我叫罗湘赣\n- 我在杭州工作，主要用 Java", StandardCharsets.UTF_8);

        // Act
        boolean rewritten = curator(memoryMd, memoryDir, true).curateNow();

        // Assert: identity → Pinned, durable → General.
        assertThat(rewritten).isTrue();
        String result = Files.readString(memoryMd, StandardCharsets.UTF_8);
        assertThat(result).isEqualTo("## Pinned\n- 我叫罗湘赣\n\n## General\n- 我在杭州工作，主要用 Java");
    }

    @Test
    void pinnedIdentityIsNeverArchivedEvenWhenStale(@TempDir Path root) throws IOException {
        // Arrange: an identity fact that is very stale.
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        Files.writeString(memoryMd, "- 我叫罗湘赣", StandardCharsets.UTF_8);
        ledger(memoryDir, "2025-01-01", "- 我叫罗湘赣"); // ancient

        // Act
        curator(memoryMd, memoryDir, true).curateNow();

        // Assert: still present under Pinned, nothing archived.
        assertThat(Files.readString(memoryMd, StandardCharsets.UTF_8)).contains("## Pinned").contains("我叫罗湘赣");
        assertThat(Files.exists(memoryDir.resolve(".decay").resolve("archive"))).isFalse();
    }

    @Test
    void reuseProtectsStaleVolatileFromArchive(@TempDir Path root) throws IOException {
        // Arrange: a stale volatile fact, but reused enough (accessCount >= reinforce=2).
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        String fact = "- 昨天临时开了一个测试端口";
        Files.writeString(memoryMd, "## Recent\n" + fact, StandardCharsets.UTF_8);
        ledger(memoryDir, "2026-01-01", fact); // stale
        MemoryAccessStore access = new MemoryAccessStore(memoryDir);
        access.bump(FactFingerprint.of(fact), TODAY.toEpochDay());
        access.bump(FactFingerprint.of(fact), TODAY.toEpochDay()); // count = 2 → protected

        // Act
        boolean rewritten = curator(memoryMd, memoryDir, true).curateNow();

        // Assert: unchanged (kept under Recent), nothing archived.
        assertThat(rewritten).isFalse();
        assertThat(Files.readString(memoryMd, StandardCharsets.UTF_8)).contains("昨天临时开了一个测试端口");
        assertThat(Files.exists(memoryDir.resolve(".decay").resolve("archive"))).isFalse();
    }

    @Test
    void secondPassIsIdempotent(@TempDir Path root) throws IOException {
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        Files.writeString(memoryMd, "- 我叫罗湘赣\n- 我在杭州工作", StandardCharsets.UTF_8);

        assertThat(curator(memoryMd, memoryDir, true).curateNow()).isTrue();  // flat → layered
        assertThat(curator(memoryMd, memoryDir, true).curateNow()).isFalse(); // stable
    }

    @Test
    void throttleSkipsWithinMinGap(@TempDir Path root) throws IOException {
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        Files.writeString(memoryMd, "- 我叫罗湘赣", StandardCharsets.UTF_8);
        MemoryLayeringDecayCurator c = new MemoryLayeringDecayCurator(memoryMd, memoryDir,
                new FactLayerClassifier(), new RetentionScorer(30, 90, 2, 5),
                new DecayArchiveWriter(memoryDir), true, Duration.ofMinutes(60), clock);

        assertThat(c.maybeCurate()).isTrue();   // first pass runs
        assertThat(c.maybeCurate()).isFalse();  // throttled (fixed clock → 0 < 60m)
    }

    @Test
    void missingOrBlankFileIsSafe(@TempDir Path root) throws IOException {
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path missing = root.resolve("MEMORY.md");

        assertThat(curator(missing, memoryDir, true).curateNow()).isFalse(); // missing

        Files.writeString(missing, "   \n", StandardCharsets.UTF_8);
        assertThat(curator(missing, memoryDir, true).curateNow()).isFalse(); // blank
    }
}
