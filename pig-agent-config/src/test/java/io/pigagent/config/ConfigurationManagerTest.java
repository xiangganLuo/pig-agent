package io.pigagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationManagerTest {

    @TempDir Path tempDir;

    @Test
    void createsDefaultConfigOnFirstRun() {
        ConfigurationManager manager = new ConfigurationManager(tempDir.resolve("app.yaml"));
        PigAgentConfig config = manager.getConfig();
        assertThat(config.getModel().getProvider()).isEqualTo("anthropic");
        assertThat(config.getAgent().getName()).isEqualTo("PigAgent");
    }

    @Test
    void agentMaxIters_defaultsTo40ForInteractiveAgents() {
        ConfigurationManager manager = new ConfigurationManager(tempDir.resolve("app.yaml"));
        assertThat(manager.getConfig().getAgent().getMaxIters()).isEqualTo(40);
    }

    @Test
    void unknownTopLevelField_isIgnored_recognizedSettingsBound() throws Exception {
        // Arrange — a config with an unknown field alongside valid permissions/compression
        Path yaml = tempDir.resolve("app.yaml");
        Files.writeString(yaml, """
                unknown-future-field: some-value
                permissions:
                  mode: bypass
                compression:
                  max-context-tokens: 12345
                """);

        // Act
        PigAgentConfig config = new ConfigurationManager(yaml).getConfig();

        // Assert — unknown field ignored, recognized settings bound (not reverted to defaults)
        assertThat(config.getPermissions().getMode()).isEqualTo("bypass");
        assertThat(config.getCompression().getMaxContextTokens()).isEqualTo(12345);
    }

    @Test
    void malformedYaml_fallsBackToDefaults() throws Exception {
        // Arrange — genuinely broken YAML (not merely an unknown field)
        Path yaml = tempDir.resolve("app.yaml");
        Files.writeString(yaml, "permissions:\n  mode: [unterminated\n");

        // Act
        PigAgentConfig config = new ConfigurationManager(yaml).getConfig();

        // Assert — existing fault tolerance: revert to defaults, no crash
        assertThat(config.getPermissions().getMode()).isEqualTo("ask");
    }

    @Test
    void planMode_defaultsOffAndRoundTrips() throws Exception {
        // Default: Plan Mode off, read-only default dir — exactly today's behavior.
        PigAgentConfig def = new ConfigurationManager(tempDir.resolve("a.yaml")).getConfig();
        assertThat(def.getPlanMode().isEnabled()).isFalse();
        assertThat(def.getPlanMode().getPlanDir()).isEqualTo("plans");
        assertThat(def.getPlanMode().isAllowShell()).isFalse();

        // A configured plan-mode block binds (exercises the setters via deserialization).
        Path yaml = tempDir.resolve("b.yaml");
        Files.writeString(yaml, """
                plan-mode:
                  enabled: true
                  plan-dir: my-plans
                  allow-shell: true
                """);
        PigAgentConfig loaded = new ConfigurationManager(yaml).getConfig();
        assertThat(loaded.getPlanMode().isEnabled()).isTrue();
        assertThat(loaded.getPlanMode().getPlanDir()).isEqualTo("my-plans");
        assertThat(loaded.getPlanMode().isAllowShell()).isTrue();
    }

    @Test
    void updateConfigPersistsAndNotifies() {
        ConfigurationManager manager = new ConfigurationManager(tempDir.resolve("app.yaml"));
        PigAgentConfig[] received = {null};
        manager.addListener(event -> received[0] = event.newConfig());
        manager.updateConfig(cfg -> cfg.getModel().setProvider("openai"));
        assertThat(manager.getConfig().getModel().getProvider()).isEqualTo("openai");
        assertThat(received[0]).isNotNull();
    }

    @Test
    void savedConfigIsOwnerOnlyOnPosix() {
        // application.yaml can hold channel credentials in plaintext — it must be 0600 at rest, like
        // models.json / mcp.json. On non-POSIX (Windows) restrictToOwner is a no-op, so guard the check.
        Path yaml = tempDir.resolve("app.yaml");
        new ConfigurationManager(yaml); // first run writes the default config
        assertThat(Files.exists(yaml)).isTrue();
        if (yaml.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(yaml);
                assertThat(perms).containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
