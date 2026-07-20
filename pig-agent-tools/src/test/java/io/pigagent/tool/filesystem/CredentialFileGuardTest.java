package io.pigagent.tool.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extracted credential-file guard: denies blacklisted files by their normalized/real path
 * (catching {@code ../} traversal), allows unlisted files, and an empty guard denies nothing.
 * Behavior is a byte-for-byte lift of the old {@code FileSystemTools} logic.
 */
class CredentialFileGuardTest {

    @TempDir
    Path tmp;

    @Test
    void deniesExactAndTraversalPaths() throws IOException {
        Path models = tmp.resolve("models.json");
        Files.writeString(models, "{}");
        CredentialFileGuard guard = new CredentialFileGuard(Set.of(models));

        assertThat(guard.isDenied(models.toString())).isTrue();
        assertThat(guard.isDenied(models)).isTrue();
        String traversal = tmp.resolve("x").resolve("..").resolve("models.json").toString();
        assertThat(guard.isDenied(traversal)).isTrue();
    }

    @Test
    void allowsUnlistedPaths() throws IOException {
        Path models = tmp.resolve("models.json");
        Files.writeString(models, "{}");
        Path normal = tmp.resolve("pom.xml");
        CredentialFileGuard guard = new CredentialFileGuard(Set.of(models));

        assertThat(guard.isDenied(normal.toString())).isFalse();
        assertThat(guard.isDenied(normal)).isFalse();
    }

    @Test
    void deniesEvenWhenFileAbsent() {
        Path missing = tmp.resolve("mcp.json"); // never created
        CredentialFileGuard guard = new CredentialFileGuard(Set.of(missing));

        assertThat(guard.isDenied(missing.toString())).isTrue();
    }

    @Test
    void emptyGuardDeniesNothing() {
        CredentialFileGuard guard = CredentialFileGuard.none();

        assertThat(guard.isEmpty()).isTrue();
        assertThat(guard.isDenied(tmp.resolve("anything").toString())).isFalse();
        assertThat(guard.isDenied((Path) null)).isFalse();
    }
}
