package io.pigagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void updateConfigPersistsAndNotifies() {
        ConfigurationManager manager = new ConfigurationManager(tempDir.resolve("app.yaml"));
        PigAgentConfig[] received = {null};
        manager.addListener(event -> received[0] = event.newConfig());
        manager.updateConfig(cfg -> cfg.getModel().setProvider("openai"));
        assertThat(manager.getConfig().getModel().getProvider()).isEqualTo("openai");
        assertThat(received[0]).isNotNull();
    }
}
