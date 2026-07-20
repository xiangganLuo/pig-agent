package io.pigagent.model;

/**
 * The category of a saved model configuration — capability {@code embedding-model-layer}.
 *
 * <p>{@link #CHAT} is a conversational/reasoning model built through a {@code ModelProtocol}; it is the
 * default so pre-existing {@code models.json} entries (which carry no {@code kind}) read back as chat.
 * {@link #EMBEDDING} is an OpenAI-compatible {@code /embeddings} endpoint consumed by the embedder seam
 * (there is no AgentScope embedding {@code Model} API), used for the vector half of memory search.
 */
public enum ModelKind {
    CHAT,
    EMBEDDING;

    /** Parse a stored/config string to a kind, defaulting to {@link #CHAT} on null/blank/unknown. */
    public static ModelKind fromString(String s) {
        if (s == null || s.isBlank()) {
            return CHAT;
        }
        try {
            return ModelKind.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }
}
