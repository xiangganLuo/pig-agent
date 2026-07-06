package io.pigagent.model;

import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelManagerResolveTest {

    private final ProtocolRegistry registry = new ProtocolRegistry();

    private StoredModel model(String id) {
        return new StoredModel(id, "openai", "k", null, "gpt-4o");
    }

    @Test
    void resolve_presentId_returnsThatModel() {
        // Arrange
        ModelStore store = mock(ModelStore.class);
        StoredModel m1 = model("m1");
        when(store.findById("m1")).thenReturn(Optional.of(m1));
        ModelManager mm = new ModelManager(registry, store);

        // Act + Assert
        assertThat(mm.resolveStoredModel("m1")).contains(m1);
    }

    @Test
    void resolve_nullId_returnsDefault() {
        // Arrange
        ModelStore store = mock(ModelStore.class);
        StoredModel def = model("def");
        when(store.getDefaultId()).thenReturn("def");
        when(store.findById("def")).thenReturn(Optional.of(def));
        ModelManager mm = new ModelManager(registry, store);

        // Act + Assert
        assertThat(mm.resolveStoredModel(null)).contains(def);
    }

    @Test
    void resolve_missingId_fallsBackToDefault() {
        // Arrange — a dangling modelId must degrade to the default, not crash
        ModelStore store = mock(ModelStore.class);
        StoredModel def = model("def");
        when(store.findById("gone")).thenReturn(Optional.empty());
        when(store.getDefaultId()).thenReturn("def");
        when(store.findById("def")).thenReturn(Optional.of(def));
        ModelManager mm = new ModelManager(registry, store);

        // Act + Assert
        assertThat(mm.resolveStoredModel("gone")).contains(def);
    }

    @Test
    void resolve_missingId_noDefault_returnsEmpty() {
        // Arrange
        ModelStore store = mock(ModelStore.class);
        when(store.findById("gone")).thenReturn(Optional.empty());
        when(store.getDefaultId()).thenReturn(null);
        ModelManager mm = new ModelManager(registry, store);

        // Act + Assert
        assertThat(mm.resolveStoredModel("gone")).isEmpty();
    }
}
