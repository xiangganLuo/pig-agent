package io.pigagent.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ConfigurationManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationManager.class);
    private static final ObjectMapper YAML_MAPPER = buildYamlMapper();

    /**
     * The YAML mapper with <b>mapper-level</b> unknown-field tolerance: an unrecognized field at
     * <em>any</em> nesting level is skipped (not fatal) so config schema drift never drops the whole
     * file to defaults, and each ignored field is logged so a typo'd key leaves a trace instead of
     * silently doing nothing. Genuinely malformed YAML still fails (→ caller reverts to defaults).
     */
    private static ObjectMapper buildYamlMapper() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                    JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName)
                    throws java.io.IOException {
                String owner = beanOrClass instanceof Class<?> c ? c.getSimpleName()
                        : (beanOrClass == null ? "?" : beanOrClass.getClass().getSimpleName());
                log.warn("忽略未知配置字段 '{}'（{}）—— 请检查是否拼写有误", propertyName, owner);
                p.skipChildren();
                return true;
            }
        });
        return mapper;
    }
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
                log.warn("Failed to load config, using defaults: {}", e.getMessage());
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
            log.warn("Failed to save config: {}", e.getMessage());
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
            catch (Exception e) { log.warn("Config listener error: {}", e.getMessage()); }
        }
    }
}
