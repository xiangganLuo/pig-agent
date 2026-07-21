package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.search.DeterministicEmbedder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The post-consolidation curator: in-place semantic dedup of {@code MEMORY.md} with structure preserved. */
class MemoryConsolidationCuratorTest {

    private static MemoryConsolidationCurator curator(Path memoryMd, Duration gap, Clock clock) {
        return new MemoryConsolidationCurator(
                memoryMd, new SemanticDeduplicator(new DeterministicEmbedder(), 0.9), gap, clock);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            count++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return count;
    }

    @Test
    void nearDuplicateBulletsAreDeduped_structurePreserved(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("MEMORY.md");
        // The first two bullets are the same fact (identical token bag; only punctuation differs).
        Files.writeString(md, "## Pinned\n- 我叫罗湘赣\n- 我叫罗湘赣。\n- 我在杭州工作\n", StandardCharsets.UTF_8);

        boolean changed = curator(md, Duration.ZERO, Clock.systemUTC()).curateNow();

        assertThat(changed).isTrue();
        String after = Files.readString(md, StandardCharsets.UTF_8);
        assertThat(after).contains("## Pinned");              // layer heading preserved
        assertThat(after).contains("我在杭州工作");           // distinct fact preserved
        assertThat(occurrences(after, "我叫罗湘赣")).isEqualTo(1); // the near-duplicate collapsed to one
    }

    @Test
    void noNearDuplicates_leavesFileUnchanged(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("MEMORY.md");
        String original = "## Pinned\n- 我叫罗湘赣\n- 我在杭州工作\n- 主要用 Java\n";
        Files.writeString(md, original, StandardCharsets.UTF_8);

        boolean changed = curator(md, Duration.ZERO, Clock.systemUTC()).curateNow();

        assertThat(changed).isFalse();
        assertThat(Files.readString(md, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void throttledWithinMinGap_doesNotRun(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("MEMORY.md");
        Clock fixed = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC);
        MemoryConsolidationCurator curator = curator(md, Duration.ofMinutes(60), fixed);

        Files.writeString(md, "- dup fact\n- dup fact\n- other\n", StandardCharsets.UTF_8);
        assertThat(curator.maybeCurate()).isTrue(); // first run dedups

        // Reintroduce a duplicate; the second call is inside the min-gap → throttled, file untouched.
        String withDup = "- dup fact\n- dup fact\n- other\n";
        Files.writeString(md, withDup, StandardCharsets.UTF_8);
        assertThat(curator.maybeCurate()).isFalse();
        assertThat(Files.readString(md, StandardCharsets.UTF_8)).isEqualTo(withDup);
    }

    @Test
    void missingFile_returnsFalse(@TempDir Path dir) {
        Path md = dir.resolve("does-not-exist.md");
        assertThat(curator(md, Duration.ZERO, Clock.systemUTC()).curateNow()).isFalse();
    }

    @Test
    void blankFile_returnsFalse(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("MEMORY.md");
        Files.writeString(md, "   \n\n", StandardCharsets.UTF_8);
        assertThat(curator(md, Duration.ZERO, Clock.systemUTC()).curateNow()).isFalse();
    }

    @Test
    void singleFact_returnsFalse(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("MEMORY.md");
        Files.writeString(md, "## Pinned\n- 我叫罗湘赣\n", StandardCharsets.UTF_8);
        assertThat(curator(md, Duration.ZERO, Clock.systemUTC()).curateNow()).isFalse();
    }
}
