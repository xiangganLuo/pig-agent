package io.pigagent.provider.openai;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;

/**
 * OpenAI-compatible protocol. Besides OpenAI itself, every OpenAI-compatible vendor
 * (Xiaomi mimo, DeepSeek, Kimi, Qwen-compat, …) is reached through this protocol by
 * supplying that vendor's base URL — no per-vendor class needed.
 */
public final class OpenAiProtocol implements ModelProtocol {
    @Override public String protocolId() { return "openai"; }
    @Override public String displayName() { return "OpenAI-compatible"; }
    @Override public String description() { return "OpenAI protocol; also mimo/DeepSeek/Kimi/Qwen-compat via base URL"; }
    @Override public String defaultModelName() { return "gpt-4o"; }
    @Override public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        var builder = OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(modelName);
        if (spec.hasBaseUrl()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }
}
