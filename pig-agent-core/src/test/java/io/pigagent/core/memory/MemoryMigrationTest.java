package io.pigagent.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for the one-time legacy-memory migration ({@code pa-memory-native}, OD9/D8):
 * {@code context/memory.md} → {@code MEMORY.md}, idempotent, with a {@code .bak} backup, fault-tolerant.
 */
class MemoryMigrationTest {

    @Test
    void migratesLegacyGlobalMemoryIntoMemoryMd(@TempDir Path ws) throws IOException {
        Path old = ws.resolve("context").resolve("memory.md");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "- User's name is 罗湘赣", StandardCharsets.UTF_8);
        Path memoryMd = ws.resolve("MEMORY.md");

        boolean migrated = MemoryMigration.migrate(old, memoryMd);

        assertThat(migrated).isTrue();
        assertThat(Files.readString(memoryMd)).contains("罗湘赣");
        assertThat(old.resolveSibling("memory.md.bak")).exists();     // original backed up
        assertThat(old).exists();                                     // original preserved (read-only)
    }

    @Test
    void isIdempotent_secondCallDoesNotReAppend(@TempDir Path ws) throws IOException {
        Path old = ws.resolve("context").resolve("memory.md");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "- fact 罗湘赣", StandardCharsets.UTF_8);
        Path memoryMd = ws.resolve("MEMORY.md");

        assertThat(MemoryMigration.migrate(old, memoryMd)).isTrue();
        String afterFirst = Files.readString(memoryMd);
        assertThat(MemoryMigration.migrate(old, memoryMd)).isFalse(); // already migrated

        assertThat(Files.readString(memoryMd)).isEqualTo(afterFirst); // not doubled
    }

    @Test
    void noOldFile_isNoOp(@TempDir Path ws) {
        boolean migrated = MemoryMigration.migrate(ws.resolve("context").resolve("memory.md"),
                ws.resolve("MEMORY.md"));

        assertThat(migrated).isFalse();
        assertThat(ws.resolve("MEMORY.md")).doesNotExist();
    }

    @Test
    void appendsToExistingMemoryMd_withoutClobbering(@TempDir Path ws) throws IOException {
        Path old = ws.resolve("context").resolve("memory.md");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "- migrated fact", StandardCharsets.UTF_8);
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "- pre-existing native fact", StandardCharsets.UTF_8);

        MemoryMigration.migrate(old, memoryMd);

        String content = Files.readString(memoryMd);
        assertThat(content).contains("pre-existing native fact").contains("migrated fact");
    }

    @Test
    void nullArgs_areNoOp() {
        assertThat(MemoryMigration.migrate(null, null)).isFalse();
    }
}
