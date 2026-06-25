package io.pigagent.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonModelStoreTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("models.json");
    }

    @Test
    void firstSavedModelBecomesDefault() {
        ModelStore store = new JsonModelStore(file());
        StoredModel m = store.save(StoredModel.create("openai", "k", null, "gpt-4o"));

        assertThat(store.findById(m.id())).isPresent();
        assertThat(store.getDefaultId()).isEqualTo(m.id());
    }

    @Test
    void secondModelDoesNotChangeDefault() {
        ModelStore store = new JsonModelStore(file());
        StoredModel first = store.save(StoredModel.create("openai", "k", null, "gpt-4o"));
        store.save(StoredModel.create("anthropic", "k2", null, "claude-sonnet-4-6"));

        assertThat(store.getDefaultId()).isEqualTo(first.id());
        assertThat(store.findAll()).hasSize(2);
    }

    @Test
    void setDefaultChangesPointer() {
        ModelStore store = new JsonModelStore(file());
        store.save(StoredModel.create("openai", "k", null, "gpt-4o"));
        StoredModel second = store.save(StoredModel.create("anthropic", "k2", null, "claude-sonnet-4-6"));

        store.setDefaultId(second.id());
        assertThat(store.getDefaultId()).isEqualTo(second.id());
    }

    @Test
    void deletingDefaultReassignsToAnother() {
        ModelStore store = new JsonModelStore(file());
        StoredModel first = store.save(StoredModel.create("openai", "k", null, "gpt-4o"));
        StoredModel second = store.save(StoredModel.create("anthropic", "k2", null, "claude-sonnet-4-6"));

        store.deleteById(first.id());
        assertThat(store.getDefaultId()).isEqualTo(second.id());
        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void persistsAcrossInstances() {
        ModelStore store = new JsonModelStore(file());
        StoredModel m = store.save(StoredModel.create("openai", "k", "https://x", "gpt-4o"));

        ModelStore reopened = new JsonModelStore(file());
        assertThat(reopened.findAll()).hasSize(1);
        assertThat(reopened.findById(m.id())).get().extracting(StoredModel::baseUrl).isEqualTo("https://x");
        assertThat(reopened.getDefaultId()).isEqualTo(m.id());
    }

    @Test
    void corruptFileIsBackedUpAndStartsEmpty() throws IOException {
        Files.writeString(file(), "{ not valid json");

        ModelStore store = new JsonModelStore(file());
        assertThat(store.findAll()).isEmpty();
        assertThat(store.getDefaultId()).isNull();
        assertThat(dir.resolve("models.json.bak")).exists();
    }
}
