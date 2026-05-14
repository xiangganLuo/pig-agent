package io.pigagent.core.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable credentials container for LLM providers.
 * Uses copy-on-write to satisfy immutability requirements.
 */
public final class ProviderCredentials {

    private final Map<String, String> data;

    public ProviderCredentials() {
        this.data = new LinkedHashMap<>();
    }

    private ProviderCredentials(Map<String, String> data) {
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public ProviderCredentials put(String key, String value) {
        var copy = new LinkedHashMap<>(this.data);
        copy.put(key, value);
        return new ProviderCredentials(copy);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    public String getRequired(String key) {
        String value = data.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required credential: " + key);
        }
        return value;
    }

    public Map<String, String> toMap() {
        return Collections.unmodifiableMap(data);
    }

    public boolean has(String key) {
        String value = data.get(key);
        return value != null && !value.isBlank();
    }
}
