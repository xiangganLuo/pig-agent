package io.pigagent.cli;

import io.agentscope.core.model.Model;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic (no network): {@link AgentBootstrap#resolveFallbackModel} — the wiring that feeds the
 * native {@code fallbackModel} into the interactive + channel {@code AgentFactory} paths — resolves a
 * distinct saved model when configured (or a distinct default) and stays {@code null} otherwise
 * (today's behavior). Uses the real {@link ModelManager}/{@link ProtocolRegistry} (models build
 * offline; only the eventual model call would touch the network).
 */
class FallbackModelWiringTest {

    @TempDir
    Path workspace;

    private record Fixture(ModelManager modelManager, StoredModel primary, StoredModel other) {
    }

    /** Two distinct saved models; the openai one is the store default (= the primary). */
    private Fixture twoModels() {
        ProtocolRegistry protocols = new ProtocolRegistry();
        protocols.register(new OpenAiProtocol());
        protocols.register(new OllamaProtocol());
        JsonModelStore store = new JsonModelStore(workspace.resolve("models.json"));
        StoredModel primary = store.save(StoredModel.create("openai", "dummy-key", null, "gpt-4o"));
        StoredModel other = store.save(
                StoredModel.create("ollama", null, "http://localhost:11434", "llama3.2"));
        store.setDefaultId(primary.id());
        return new Fixture(new ModelManager(protocols, store), primary, other);
    }

    private static PigAgentConfig configWithFallback(String fallbackId) {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.getModel().setFallbackModelId(fallbackId);
        return cfg;
    }

    @Test
    void configuredDistinctFallback_isWired() {
        // Arrange
        Fixture f = twoModels();
        PigAgentConfig cfg = configWithFallback(f.other().id());

        // Act
        Model fallback = AgentBootstrap.resolveFallbackModel(cfg, f.modelManager(), f.primary());

        // Assert — a non-null fallback model is wired when configured
        assertThat(fallback).isNotNull();
    }

    @Test
    void noConfigAndNoDistinctDefault_isNull() {
        // Arrange — single model = the primary = the default, so no distinct fallback exists
        ProtocolRegistry protocols = new ProtocolRegistry();
        protocols.register(new OpenAiProtocol());
        JsonModelStore store = new JsonModelStore(workspace.resolve("models.json"));
        StoredModel only = store.save(StoredModel.create("openai", "dummy-key", null, "gpt-4o"));
        store.setDefaultId(only.id());
        ModelManager modelManager = new ModelManager(protocols, store);

        // Act
        Model fallback = AgentBootstrap.resolveFallbackModel(
                new PigAgentConfig(), modelManager, only);

        // Assert — null preserves today's behavior (no fallback)
        assertThat(fallback).isNull();
    }

    @Test
    void configuredIdEqualToPrimary_fallsBackToNullWhenNoDistinctDefault() {
        // Arrange — the configured fallback id IS the primary (degenerate); default == primary too
        ProtocolRegistry protocols = new ProtocolRegistry();
        protocols.register(new OpenAiProtocol());
        JsonModelStore store = new JsonModelStore(workspace.resolve("models.json"));
        StoredModel only = store.save(StoredModel.create("openai", "dummy-key", null, "gpt-4o"));
        store.setDefaultId(only.id());
        ModelManager modelManager = new ModelManager(protocols, store);

        // Act
        Model fallback = AgentBootstrap.resolveFallbackModel(
                configWithFallback(only.id()), modelManager, only);

        // Assert — a fallback equal to the primary is not a fallback → null
        assertThat(fallback).isNull();
    }

    @Test
    void unresolvableConfiguredId_degradesToDistinctDefault() {
        // Arrange — a bogus configured id, but a distinct default (ollama) exists to fail over to.
        // Make the default distinct from the primary so the implicit branch fires.
        ProtocolRegistry protocols = new ProtocolRegistry();
        protocols.register(new OpenAiProtocol());
        protocols.register(new OllamaProtocol());
        JsonModelStore store = new JsonModelStore(workspace.resolve("models.json"));
        StoredModel primary = store.save(StoredModel.create("openai", "dummy-key", null, "gpt-4o"));
        StoredModel def = store.save(
                StoredModel.create("ollama", null, "http://localhost:11434", "llama3.2"));
        store.setDefaultId(def.id()); // default distinct from the primary
        ModelManager modelManager = new ModelManager(protocols, store);

        // Act
        Model fallback = AgentBootstrap.resolveFallbackModel(
                configWithFallback("does-not-exist"), modelManager, primary);

        // Assert — a bad configured id degrades to the distinct default, not an exception
        assertThat(fallback).isNotNull();
    }
}
