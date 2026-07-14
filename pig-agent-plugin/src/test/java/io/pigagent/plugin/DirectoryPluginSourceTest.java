package io.pigagent.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link DirectoryPluginSource}: it is fault-tolerant across the discovery boundary —
 * a {@code null} / missing / empty / no-jar directory returns an empty list without throwing, and a
 * jar whose service entry cannot be resolved is tolerated (the real {@code URLClassLoader} +
 * {@code ServiceLoader} code path runs and swallows the bad entry). End-to-end loading of a valid
 * external plugin from a jar shares the same {@code ServiceLoader} mechanism covered by
 * {@link ServiceLoaderPluginSourceTest}.
 */
class DirectoryPluginSourceTest {

    @Test
    void nullDirectory_returnsEmpty() {
        // Arrange
        DirectoryPluginSource source = new DirectoryPluginSource(null);

        // Act + Assert
        assertThat(source.discover()).isEmpty();
    }

    @Test
    void missingDirectory_returnsEmpty(@TempDir Path tmp) {
        // Arrange
        DirectoryPluginSource source = new DirectoryPluginSource(tmp.resolve("does-not-exist"));

        // Act + Assert
        assertThat(source.discover()).isEmpty();
    }

    @Test
    void emptyDirectory_returnsEmpty(@TempDir Path tmp) {
        // Arrange
        DirectoryPluginSource source = new DirectoryPluginSource(tmp);

        // Act + Assert
        assertThat(source.discover()).isEmpty();
    }

    @Test
    void directoryWithNonJarFiles_returnsEmpty(@TempDir Path tmp) throws Exception {
        // Arrange — a stray non-jar file is ignored
        Files.writeString(tmp.resolve("readme.txt"), "not a jar");
        DirectoryPluginSource source = new DirectoryPluginSource(tmp);

        // Act + Assert
        assertThat(source.discover()).isEmpty();
    }

    @Test
    void jarWithUnresolvableServiceEntry_isToleratedNoThrow() throws Exception {
        // Arrange — a real jar declaring a Plugin service class that does not exist. NOT @TempDir:
        // the source keeps a URLClassLoader open over the jar (by design — see DirectoryPluginSource),
        // which locks the file on Windows, so we manage cleanup best-effort ourselves.
        Path dir = Files.createTempDirectory("plugin-dir-source-test");
        try {
            writeJarWithServiceEntry(dir.resolve("bad-plugin.jar"), "com.example.NoSuchPlugin");
            DirectoryPluginSource source = new DirectoryPluginSource(dir);

            // Act + Assert — the URLClassLoader + ServiceLoader path runs and swallows the bad entry
            assertThatCode(source::discover).doesNotThrowAnyException();
            assertThat(source.discover()).allSatisfy(p -> assertThat(p).isNotNull());
        } finally {
            deleteQuietly(dir);
        }
    }

    /** Best-effort recursive delete that ignores files still locked by an open classloader. */
    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // jar may be locked by the still-open URLClassLoader on Windows — leave it
                }
            });
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Write a minimal jar containing only a {@code META-INF/services/...Plugin} declaration. */
    private static void writeJarWithServiceEntry(Path jar, String declaredClass) throws Exception {
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry("META-INF/services/io.pigagent.plugin.Plugin"));
            jos.write((declaredClass + "\n").getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    @Test
    void discover_returnsAList(@TempDir Path tmp) {
        // Arrange — sanity: the source never returns null
        DirectoryPluginSource source = new DirectoryPluginSource(tmp);

        // Act
        List<Plugin> plugins = source.discover();

        // Assert
        assertThat(plugins).isNotNull();
    }
}
