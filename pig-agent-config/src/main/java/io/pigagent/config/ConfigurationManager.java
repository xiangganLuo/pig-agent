package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ConfigurationManager {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private final Path configPath;
    private volatile PigAgentConfig config;
    private final List<Consumer<ConfigurationChangedEvent>> listeners = new ArrayList<>();

    public ConfigurationManager(Path configPath) {
        this.configPath = configPath;
        this.config = loadOrCreate();
    }

    public PigAgentConfig getConfig() { return config; }

    public void updateConfig(Consumer<PigAgentConfig> updater) {
        PigAgentConfig oldConfig = copyConfig(config);
        updater.accept(config);
        saveConfig();
        notifyListeners(new ConfigurationChangedEvent(oldConfig, config));
    }

    public void addListener(Consumer<ConfigurationChangedEvent> listener) {
        listeners.add(listener);
    }

    private PigAgentConfig loadOrCreate() {
        if (Files.exists(configPath)) {
            try {
                return YAML_MAPPER.readValue(configPath.toFile(), PigAgentConfig.class);
            } catch (IOException e) {
                System.err.println("Warning: Failed to load config, using defaults: " + e.getMessage());
            }
        }
        PigAgentConfig defaultConfig = new PigAgentConfig();
        saveConfig(defaultConfig);
        return defaultConfig;
    }

    private void saveConfig() { saveConfig(config); }

    private void saveConfig(PigAgentConfig cfg) {
        try {
            Files.createDirectories(configPath.getParent());
            YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), cfg);
        } catch (IOException e) {
            System.err.println("Warning: Failed to save config: " + e.getMessage());
        }
    }

    private PigAgentConfig copyConfig(PigAgentConfig original) {
        try {
            byte[] json = YAML_MAPPER.writeValueAsBytes(original);
            return YAML_MAPPER.readValue(json, PigAgentConfig.class);
        } catch (IOException e) {
            return new PigAgentConfig();
        }
    }

    private void notifyListeners(ConfigurationChangedEvent event) {
        for (var listener : listeners) {
            try { listener.accept(event); }
            catch (Exception e) { System.err.println("Warning: Config listener error: " + e.getMessage()); }
        }
    }
}
