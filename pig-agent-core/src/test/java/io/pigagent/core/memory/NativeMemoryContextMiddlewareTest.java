package io.pigagent.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link NativeMemoryContextMiddleware} — the injection half of native two-layer
 * memory ({@code pa-memory-native}, OD2-A): the consolidated {@code MEMORY.md} is appended to the
 * system prompt, injection is stable while the file is unchanged (prefix-cache friendly), and a
 * missing/blank/unreadable file leaves the prompt untouched.
 */
class NativeMemoryContextMiddlewareTest {

    @Test
    void injectsMemoryMdContentIntoSystemPrompt(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "- User's name is 罗湘赣\n- Prefers Chinese replies", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory);

        String result = mw.onSystemPrompt(null, null, "BASE PROMPT").block();

        assertThat(result).startsWith("BASE PROMPT");
        assertThat(result).contains(NativeMemoryContextMiddleware.MEMORY_HEADER);
        assertThat(result).contains("罗湘赣").contains("Prefers Chinese replies");
    }

    @Test
    void injectionIsStableWhileFileUnchanged(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "- fact one", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory);

        String first = mw.onSystemPrompt(null, null, "SYS").block();
        String second = mw.onSystemPrompt(null, null, "SYS").block();

        assertThat(second).isEqualTo(first); // byte-stable within a session (prefix-cache friendly)
    }

    @Test
    void missingMemoryFile_leavesPromptUnchanged(@TempDir Path ws) {
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(ws.resolve("MEMORY.md"));

        String result = mw.onSystemPrompt(null, null, "ONLY THE BASE").block();

        assertThat(result).isEqualTo("ONLY THE BASE");
    }

    @Test
    void blankMemoryFile_leavesPromptUnchanged(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "   \n\n  ", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory);

        String result = mw.onSystemPrompt(null, null, "BASE").block();

        assertThat(result).isEqualTo("BASE");
    }
}
