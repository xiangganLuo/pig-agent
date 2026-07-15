package io.pigagent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonMcpStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("mcp.json");
    }

    private static McpServerSpec stdio(String name) {
        return new McpServerSpec(name, "npx", List.of("-y", "srv"), Map.of(), null, false, Map.of(), true);
    }

    private static McpServerSpec url(String name, String u) {
        return new McpServerSpec(name, null, List.of(), Map.of(), u, false, Map.of("Authorization", "Bearer x"), true);
    }

    @Test
    void saveThenFindByNameRoundTrips() {
        McpStore store = new JsonMcpStore(file());
        store.save(url("remote", "https://h/sse"));

        assertThat(store.findByName("remote")).isPresent();
        assertThat(store.findByName("remote").get().url()).isEqualTo("https://h/sse");
        assertThat(store.findByName("remote").get().headers()).containsEntry("Authorization", "Bearer x");
    }

    @Test
    void findAllReturnsAll() {
        McpStore store = new JsonMcpStore(file());
        store.save(stdio("a"));
        store.save(url("b", "https://h/sse"));

        assertThat(store.findAll()).extracting(McpServerSpec::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void saveSameNameUpserts() {
        McpStore store = new JsonMcpStore(file());
        store.save(stdio("a"));
        store.save(url("a", "https://h/sse"));

        assertThat(store.findAll()).hasSize(1);
        assertThat(store.findByName("a")).get().extracting(McpServerSpec::isStdio).isEqualTo(false);
    }

    @Test
    void deleteByNameRemoves() {
        McpStore store = new JsonMcpStore(file());
        store.save(stdio("a"));
        store.deleteByName("a");

        assertThat(store.findByName("a")).isEmpty();
    }

    @Test
    void persistsAcrossInstances() {
        new JsonMcpStore(file()).save(stdio("a"));

        McpStore reopened = new JsonMcpStore(file());
        assertThat(reopened.findAll()).extracting(McpServerSpec::name).containsExactly("a");
    }

    @Test
    void savedCredentialFileWritesSuccessfully_andIsOwnerOnlyOnPosix() throws IOException {
        Path f = file();
        McpStore store = new JsonMcpStore(f);
        store.save(url("remote", "https://h/sse")); // headers hold a Bearer token

        assertThat(f).exists(); // write succeeds on every platform
        boolean posix = Files.getFileAttributeView(f, PosixFileAttributeView.class) != null;
        assumeTrue(posix, "POSIX file permissions not supported on this platform");
        assertThat(Files.getPosixFilePermissions(f))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void corruptFileBackedUpAndStartsEmpty() throws IOException {
        Files.writeString(file(), "{ not valid json");

        McpStore store = new JsonMcpStore(file());
        assertThat(store.findAll()).isEmpty();
        assertThat(dir.resolve("mcp.json.bak")).exists();
    }
}
