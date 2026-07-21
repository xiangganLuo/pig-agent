package io.pigagent.core.memory.decay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryAccessStore} — the fault-tolerant reuse-signal sidecar (M-C, D2).
 */
class MemoryAccessStoreTest {

    @Test
    void bumpIncrementsCountAndPersistsAcrossInstances(@TempDir Path memoryDir) {
        // Arrange
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);
        String fp = FactFingerprint.of("我在杭州工作");

        // Act
        store.bump(fp, 100L);
        store.bump(fp, 101L);

        // Assert: a fresh instance reads the persisted count (sidecar durable across passes).
        MemoryAccessStore reloaded = new MemoryAccessStore(memoryDir);
        assertThat(reloaded.accessCount(fp)).isEqualTo(2);
    }

    @Test
    void unknownFingerprintCountsZero(@TempDir Path memoryDir) {
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);

        assertThat(store.accessCount("never-seen")).isZero();
        assertThat(store.accessCount("")).isZero();
        assertThat(store.accessCount(null)).isZero();
    }

    @Test
    void blankFingerprintBumpIsIgnored(@TempDir Path memoryDir) {
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);

        store.bump("", 1L);
        store.bump(null, 1L);

        // No .decay dir/file was created for an ignored bump; nothing to read back.
        assertThat(store.accessCount("")).isZero();
    }

    @Test
    void persistsUnderDotDecaySubdir(@TempDir Path memoryDir) {
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);

        store.bump(FactFingerprint.of("我叫罗湘赣"), 5L);

        assertThat(Files.isRegularFile(memoryDir.resolve(".decay").resolve("access-state.json"))).isTrue();
    }

    @Test
    void corruptSidecarLoadsAsEmpty(@TempDir Path memoryDir) throws IOException {
        // Arrange: write a corrupt sidecar file.
        Files.createDirectories(memoryDir.resolve(".decay"));
        Files.writeString(memoryDir.resolve(".decay").resolve("access-state.json"),
                "{not valid json", StandardCharsets.UTF_8);

        // Act: constructing over it must not throw and reads as empty.
        MemoryAccessStore store = new MemoryAccessStore(memoryDir);

        // Assert
        assertThat(store.accessCount("anything")).isZero();
    }
}
