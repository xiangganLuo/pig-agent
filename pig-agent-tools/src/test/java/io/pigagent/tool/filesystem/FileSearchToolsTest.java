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
 * {@code searchFiles} (pure-Java content/regex search) and {@code findFiles} (glob filename lookup):
 * matching + line numbers, case sensitivity, file-name restriction, bounded output, binary/credential
 * skipping, glob traversal, and canonical {@code {"error"}} on bad input. Uses a real temp tree; no
 * external process is ever spawned (the tools are pure Java by construction).
 */
class FileSearchToolsTest {

    @TempDir
    Path root;

    private FileSearchTools tools;

    @BeforeEach
    void setUp() throws IOException {
        tools = new FileSearchTools();
        Files.writeString(root.resolve("a.txt"), "alpha\nBETA\ngamma");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("b.java"), "class Beta {}\n// beta note");
        Files.writeString(root.resolve("c.md"), "nothing here");
    }

    // --- searchFiles ---

    @Test
    void searchReturnsPathAndLineNumber() {
        String r = tools.searchFiles("alpha", root.toString(), null, null);

        assertThat(r).contains("a.txt:1:").contains("alpha");
    }

    @Test
    void searchCaseSensitiveByDefaultAndInsensitiveWhenAsked() {
        assertThat(tools.searchFiles("beta", root.toString(), null, null))
                .contains("b.java").doesNotContain("a.txt:2");

        assertThat(tools.searchFiles("beta", root.toString(), null, "true"))
                .contains("a.txt:2");
    }

    @Test
    void searchFilePatternRestrictsByName() {
        String r = tools.searchFiles("[Bb]eta", root.toString(), "*.java", null);

        assertThat(r).contains("b.java").doesNotContain("a.txt");
    }

    @Test
    void searchNoMatchReturnsFriendlyMessageNotError() {
        String r = tools.searchFiles("zzzz", root.toString(), null, null);

        assertThat(r).contains("No matches").doesNotContain("\"error\"");
    }

    @Test
    void invalidRegexReturnsError() {
        String r = tools.searchFiles("[unclosed", root.toString(), null, null);

        assertThat(r).contains("\"error\"").contains("invalid regex");
    }

    @Test
    void searchOnNonExistentDirReturnsError() {
        String r = tools.searchFiles("x", root.resolve("nope").toString(), null, null);

        assertThat(r).contains("\"error\"");
    }

    @Test
    void searchSkipsBinaryFiles() throws IOException {
        Files.write(root.resolve("bin.dat"), new byte[]{'x', 0, 'x', 'y'});

        String r = tools.searchFiles("x", root.toString(), "bin.dat", null);

        assertThat(r).contains("No matches");
    }

    @Test
    void searchSkipsCredentialFiles() throws IOException {
        Path models = root.resolve("models.json");
        Files.writeString(models, "{\"apiKey\":\"sk-supersecret\"}");
        FileSearchTools guarded = new FileSearchTools(Set.of(models));

        String r = guarded.searchFiles("supersecret", root.toString(), null, null);

        assertThat(r).contains("No matches");
        assertThat(r).doesNotContain("sk-supersecret");
    }

    @Test
    void searchOutputIsBoundedPerFile() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("match line ").append(i).append('\n');
        }
        Files.writeString(root.resolve("big.log"), sb.toString());

        String r = tools.searchFiles("match", root.toString(), "big.log", null);

        long lines = r.lines().filter(l -> l.startsWith("big.log:")).count();
        assertThat(lines).isLessThanOrEqualTo(FileSearchTools.MAX_MATCHES_PER_FILE);
    }

    // --- findFiles ---

    @Test
    void findGlobMatchesAcrossTree() {
        String r = tools.findFiles("**/*.java", root.toString());

        assertThat(r).contains("sub/b.java");
    }

    @Test
    void findTopLevelGlob() {
        String r = tools.findFiles("*.txt", root.toString());

        assertThat(r).contains("a.txt").doesNotContain("b.java");
    }

    @Test
    void findNoMatchReturnsFriendlyMessageNotError() {
        String r = tools.findFiles("**/*.py", root.toString());

        assertThat(r).contains("No files match").doesNotContain("\"error\"");
    }

    @Test
    void findEmptyPatternReturnsError() {
        assertThat(tools.findFiles("", root.toString())).contains("\"error\"");
    }

    @Test
    void findSkipsCredentialFiles() throws IOException {
        Path models = root.resolve("models.json");
        Files.writeString(models, "{}");
        FileSearchTools guarded = new FileSearchTools(Set.of(models));

        String r = guarded.findFiles("*.json", root.toString());

        assertThat(r).doesNotContain("models.json");
    }
}
