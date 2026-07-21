package io.pigagent.core.memory.decay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link MemoryAccessRecorder} — the reuse-signal seam (M-C, D2).
 */
class MemoryAccessRecorderTest {

    @Test
    void noopRecorderHasZeroSideEffects(@TempDir Path memoryDir) {
        // Arrange
        MemoryAccessRecorder recorder = MemoryAccessRecorder.noop();

        // Act
        recorder.record(List.of("我在杭州工作", "我叫罗湘赣"));

        // Assert: nothing was written; a store over the dir sees no counts.
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);
        assertThat(store.accessCount(FactFingerprint.of("我在杭州工作"))).isZero();
    }

    @Test
    void storeBackedRecorderBumpsEachFactFingerprint(@TempDir Path memoryDir) {
        // Arrange
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);
        MemoryAccessRecorder recorder = MemoryAccessRecorder.backedBy(store, () -> 42L);

        // Act
        recorder.record(List.of("我在杭州工作", "我在杭州工作"));

        // Assert: two hits on the same fact → count 2.
        assertThat(store.accessCount(FactFingerprint.of("我在杭州工作"))).isEqualTo(2);
    }

    @Test
    void nullOrEmptyHitsAreIgnored(@TempDir Path memoryDir) {
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);
        MemoryAccessRecorder recorder = MemoryAccessRecorder.backedBy(store, () -> 1L);

        assertThatCode(() -> {
            recorder.record(null);
            recorder.record(List.of());
        }).doesNotThrowAnyException();
    }
}
