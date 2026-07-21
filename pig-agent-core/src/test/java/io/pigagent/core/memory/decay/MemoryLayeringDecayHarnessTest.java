package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;
import io.pigagent.core.memory.quality.eval.MemoryEvalFixture;
import io.pigagent.core.memory.quality.eval.MemoryQualityAssertions;
import io.pigagent.core.memory.search.DeterministicEmbedder;
import io.pigagent.core.memory.search.Embedder;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the memory <b>layering/decay eval harness</b> works offline with a deterministic driver (M-C,
 * task 6.3): a fixed {@link MemoryEvalFixture} drives a curate pass with controlled recency (dated
 * ledgers) + reuse (the access sidecar), and the reused M-D {@link MemoryQualityAssertions} plus the new
 * {@link MemoryDecayAssertions} assert the three quality dimensions (remembered / no near-duplicates / no
 * key fact lost) AND the three decay dimensions (stale degraded/archived / Pinned permanent / reused
 * retained). No live model — the real aging-quality baseline is a deferred {@code *IT}.
 */
class MemoryLayeringDecayHarnessTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 21);
    private final Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private final Embedder embedder = new DeterministicEmbedder();

    private static final String IDENTITY = "- 我叫罗湘赣";
    private static final String GENERAL_FRESH = "- 我在杭州工作，主要用 Java";
    private static final String VOLATILE_STALE = "- 昨天临时开了一个测试端口";
    private static final String VOLATILE_REUSED = "- 刚才配置的临时代理";

    private void ledger(Path memoryDir, String date, String content) throws IOException {
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve(date + ".md"), content, StandardCharsets.UTF_8);
    }

    @Test
    void harnessAssertsQualityAndDecayDimensionsAfterCurate(@TempDir Path root) throws IOException {
        // Arrange: a flat MEMORY.md with an identity fact, a fresh durable fact, a stale episodic fact,
        // and a stale-but-reused episodic fact.
        Path memoryDir = root.resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryMd = root.resolve("MEMORY.md");
        String before = String.join("\n", IDENTITY, GENERAL_FRESH, VOLATILE_STALE, VOLATILE_REUSED);
        Files.writeString(memoryMd, before, StandardCharsets.UTF_8);

        ledger(memoryDir, "2025-01-01", IDENTITY + "\n" + VOLATILE_STALE + "\n" + VOLATILE_REUSED); // stale
        ledger(memoryDir, "2026-07-20", GENERAL_FRESH);                                             // fresh

        MemoryAccessStore access = new MemoryAccessStore(memoryDir);
        access.bump(FactFingerprint.of(VOLATILE_REUSED), TODAY.toEpochDay());
        access.bump(FactFingerprint.of(VOLATILE_REUSED), TODAY.toEpochDay()); // reused → protected

        MemoryEvalFixture fixture = MemoryEvalFixture.personalFactsWithDuplicate();

        // Act: run the layering/decay curator (auto-archive on).
        new MemoryLayeringDecayCurator(memoryMd, memoryDir, new FactLayerClassifier(),
                new RetentionScorer(30, 90, 2, 5), new DecayArchiveWriter(memoryDir),
                true, Duration.ZERO, clock).curateNow();

        String after = Files.readString(memoryMd, StandardCharsets.UTF_8);
        String archive = Files.readString(
                memoryDir.resolve(".decay").resolve("archive").resolve("2026-07.md"), StandardCharsets.UTF_8);

        // Assert — quality (reused M-D harness): key facts remembered, no near-duplicates, none lost.
        MemoryQualityAssertions.assertKeyFactsRemembered(after, fixture);
        MemoryQualityAssertions.assertNoNearDuplicates(after, fixture.dedupThreshold(), embedder);
        MemoryQualityAssertions.assertNoKeyFactLost(before, after, fixture);

        // Assert — decay (M-C): identity pinned + never archived, stale archived, reused retained.
        MemoryDecayAssertions.assertFactInLayer(after, IDENTITY, Layer.PINNED);
        MemoryDecayAssertions.assertPinnedNeverArchived(archive, List.of(IDENTITY));
        MemoryDecayAssertions.assertStaleFactArchived(archive, after, VOLATILE_STALE);
        MemoryDecayAssertions.assertReusedFactRetained(after, VOLATILE_REUSED);
    }

    @Test
    void decayAssertionsFlagViolations() {
        // The framework must catch a pinned fact that was wrongly archived.
        assertThatThrownBy(() ->
                MemoryDecayAssertions.assertPinnedNeverArchived("- 我叫罗湘赣", List.of("我叫罗湘赣")))
                .isInstanceOf(AssertionError.class);
        // ...and a stale fact that was NOT archived.
        assertThatThrownBy(() ->
                MemoryDecayAssertions.assertStaleFactArchived("", "- 昨天临时开了一个测试端口",
                        "昨天临时开了一个测试端口"))
                .isInstanceOf(AssertionError.class);
    }
}
