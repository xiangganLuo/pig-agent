package io.pigagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores model configurations in a single {@code models.json}:
 * <pre>{ "defaultModelId": "...", "models": [ { id, providerId, apiKey, baseUrl, modelName }, ... ] }</pre>
 *
 * <p>Fault tolerance (§八): if the file is present but unreadable, it is backed up to
 * {@code models.json.bak} and the store starts empty, so the app falls back to onboarding
 * instead of crashing.
 */
public final class JsonModelStore implements ModelStore {

    private static final Logger log = LoggerFactory.getLogger(JsonModelStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private Data data;

    public JsonModelStore(Path file) {
        this.file = file;
        this.data = load();
    }

    private Data load() {
        if (!Files.exists(file)) {
            return new Data();
        }
        try {
            Data loaded = MAPPER.readValue(file.toFile(), Data.class);
            return loaded != null ? loaded : new Data();
        } catch (IOException e) {
            backupCorrupt();
            return new Data();
        }
    }

    private void backupCorrupt() {
        try {
            Files.move(file, file.resolveSibling("models.json.bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
        log.warn("models.json was unreadable; backed up to models.json.bak, starting fresh.");
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            restrictToOwner(file);
        } catch (IOException e) {
            log.error("Failed to write models.json: {}", e.getMessage(), e);
        }
    }

    /**
     * Restrict the credential file to owner read/write ({@code 0600}) on POSIX platforms. On
     * non-POSIX platforms (e.g. Windows) this is silently ignored — the write already succeeded and
     * directory permissions are relied upon there.
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem or transient error — do not fail the (already successful) write
        }
    }

    @Override
    public synchronized List<StoredModel> findAll() {
        List<StoredModel> result = new ArrayList<>();
        for (Entry e : data.models) {
            result.add(e.toModel());
        }
        return result;
    }

    @Override
    public synchronized Optional<StoredModel> findById(String id) {
        return data.models.stream().filter(e -> e.id.equals(id)).map(Entry::toModel).findFirst();
    }

    @Override
    public synchronized StoredModel save(StoredModel model) {
        data.models.removeIf(e -> e.id.equals(model.id()));
        data.models.add(Entry.from(model));
        if (data.defaultModelId == null) {
            data.defaultModelId = model.id();
        }
        persist();
        return model;
    }

    @Override
    public synchronized void deleteById(String id) {
        data.models.removeIf(e -> e.id.equals(id));
        if (id.equals(data.defaultModelId)) {
            data.defaultModelId = data.models.isEmpty() ? null : data.models.get(0).id;
        }
        // Deleting the default embedding model clears its pointer (fall back to "no embedding model"
        // → BM25-only) rather than leaving it dangling — mirrors the default-chat-model handling above.
        if (id.equals(data.defaultEmbeddingModelId)) {
            data.defaultEmbeddingModelId = null;
        }
        persist();
    }

    @Override
    public synchronized String getDefaultId() {
        return data.defaultModelId;
    }

    @Override
    public synchronized void setDefaultId(String id) {
        data.defaultModelId = id;
        persist();
    }

    @Override
    public synchronized String getDefaultEmbeddingModelId() {
        return data.defaultEmbeddingModelId;
    }

    @Override
    public synchronized void setDefaultEmbeddingModelId(String id) {
        data.defaultEmbeddingModelId = id;
        persist();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Data {
        public String defaultModelId;
        public String defaultEmbeddingModelId;
        public List<Entry> models = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Entry {
        public String id;
        public String protocolId;
        public String apiKey;
        public String baseUrl;
        public String modelName;
        /** Serialized model category; a missing field (older files) deserializes to null → CHAT. */
        public String kind;

        static Entry from(StoredModel m) {
            Entry e = new Entry();
            e.id = m.id();
            e.protocolId = m.protocolId();
            e.apiKey = m.apiKey();
            e.baseUrl = m.baseUrl();
            e.modelName = m.modelName();
            e.kind = m.kind().name();
            return e;
        }

        StoredModel toModel() {
            return new StoredModel(id, protocolId, apiKey, baseUrl, modelName, ModelKind.fromString(kind));
        }
    }
}
