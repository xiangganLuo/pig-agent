package io.pigagent.core.protocol;

import io.agentscope.core.model.Model;

/**
 * A model access <b>protocol standard</b> (e.g. {@code openai}, {@code anthropic},
 * {@code gemini}, {@code ollama}, {@code dashscope}) rather than a specific vendor.
 *
 * <p>The module defines a small set of mainstream protocols; any compatible vendor is reached
 * by selecting a protocol and supplying a base URL / API key / model name via {@link ModelSpec}.
 * For example every OpenAI-compatible vendor (Xiaomi mimo, DeepSeek, Kimi, Qwen-compat, …) is
 * accessed through the {@code openai} protocol plus its own base URL — no per-vendor class.
 */
public interface ModelProtocol {

    /** Unique protocol identifier, e.g. "openai", "anthropic", "gemini", "ollama", "dashscope". */
    String protocolId();

    /** Human-readable display name. */
    String displayName();

    /** Description shown during onboarding / protocol listing. */
    String description();

    /** Default model name suggested for this protocol. */
    String defaultModelName();

    /** Build a Model from a fully-resolved spec (protocol id, optional api key, optional base url, model name). */
    Model createModel(ModelSpec spec);

    /** Whether this protocol needs an API key (false for local protocols like Ollama). */
    default boolean requiresApiKey() {
        return true;
    }

    /** Whether a custom base URL / endpoint is meaningful for this protocol. */
    default boolean supportsBaseUrl() {
        return false;
    }
}
