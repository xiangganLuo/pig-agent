package io.pigagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    void updateConfigPersistsAndNotifies() {
        ConfigurationManager manager = new ConfigurationManager(tempDir.resolve("app.yaml"));
        PigAgentConfig[] received = {null};
        manager.addListener(event -> received[0] = event.newConfig());
        manager.updateConfig(cfg -> cfg.getModel().setProvider("openai"));
        assertThat(manager.getConfig().getModel().getProvider()).isEqualTo("openai");
        assertThat(received[0]).isNotNull();
    }
}
