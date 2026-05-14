package io.pigagent.config;

public record ConfigurationChangedEvent(
        PigAgentConfig oldConfig,
        PigAgentConfig newConfig
) {}
