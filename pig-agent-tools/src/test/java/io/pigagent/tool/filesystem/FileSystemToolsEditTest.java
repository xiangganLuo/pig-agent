package io.pigagent.tool.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code editFile}: targeted literal replacement on an existing file — unique-match default,
 * {@code replace_all}, idempotency-safe "not found", no-op rejection, credential guard, and literal
 * (non-regex) semantics.
 */
class FileSystemToolsEditTest {

    @TempDir
    Path tmp;

    private FileSystemTools tools;
    private Path file;

    @BeforeEach
    void setUp() {
        tools = new FileSystemTools();
        file = tmp.resolve("f.txt");
    }

    @Test
    void editsUniqueMatch() throws IOException {
        Files.writeString(file, "hello world");

        String r = tools.editFile(file.toString(), "world", "there", null);

        assertThat(r).contains("Edited").contains("1 replacement");
        assertThat(Files.readString(file)).isEqualTo("hello there");
    }

    @Test
    void missingFileReturnsErrorAndDoesNotCreate() {
        Path missing = tmp.resolve("nope.txt");

        String r = tools.editFile(missing.toString(), "a", "b", null);

        assertThat(r).contains("\"error\"").contains("file not found");
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void ambiguousMatchRejectedAndFileUnchanged() throws IOException {
        Files.writeString(file, "x x x");

        String r = tools.editFile(file.toString(), "x", "y", null);

        assertThat(r).contains("\"error\"").contains("ambiguous");
        assertThat(Files.readString(file)).isEqualTo("x x x");
    }

    @Test
    void notFoundRejectedAndFileUnchanged_idempotencySafe() throws IOException {
        Files.writeString(file, "hello there");

        // Re-applying an already-applied edit: oldString gone → error, file untouched (never corrupts).
        String r = tools.editFile(file.toString(), "world", "there", null);

        assertThat(r).contains("\"error\"").contains("not found");
        assertThat(Files.readString(file)).isEqualTo("hello there");
    }

    @Test
    void replaceAllReplacesEveryOccurrence() throws IOException {
        Files.writeString(file, "a a a");

        String r = tools.editFile(file.toString(), "a", "b", "true");

        assertThat(r).contains("3 replacements");
        assertThat(Files.readString(file)).isEqualTo("b b b");
    }

    @Test
    void noOpEditRejected() throws IOException {
        Files.writeString(file, "same");

        String r = tools.editFile(file.toString(), "same", "same", null);

        assertThat(r).contains("\"error\"").contains("no-op");
        assertThat(Files.readString(file)).isEqualTo("same");
    }

    @Test
    void emptyOldStringRejected() throws IOException {
        Files.writeString(file, "content");

        String r = tools.editFile(file.toString(), "", "x", null);

        assertThat(r).contains("\"error\"");
        assertThat(Files.readString(file)).isEqualTo("content");
    }

    @Test
    void deniesCredentialFileAndDoesNotModify() throws IOException {
        Path models = tmp.resolve("models.json");
        Files.writeString(models, "{\"apiKey\":\"sk-secret\"}");
        FileSystemTools guarded = new FileSystemTools(Set.of(models));
        String before = Files.readString(models);

        String r = guarded.editFile(models.toString(), "sk-secret", "x", null);

        assertThat(r).contains("access denied");
        assertThat(Files.readString(models)).isEqualTo(before);
    }

    @Test
    void replacementIsLiteralNotRegex() throws IOException {
        Files.writeString(file, "price=$1.00 (a.b)");

        String r = tools.editFile(file.toString(), "$1.00", "$2.50", null);

        assertThat(r).contains("Edited");
        assertThat(Files.readString(file)).isEqualTo("price=$2.50 (a.b)");
    }
}
