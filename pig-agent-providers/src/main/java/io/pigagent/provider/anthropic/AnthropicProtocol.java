package io.pigagent.provider.anthropic;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.AnthropicChatModel;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;

/** Anthropic (Claude) protocol. */
public final class AnthropicProtocol implements ModelProtocol {
    @Override public String protocolId() { return "anthropic"; }
    @Override public String displayName() { return "Anthropic (Claude)"; }
    @Override public String description() { return "Anthropic messages protocol (Claude series)"; }
    @Override public String defaultModelName() { return "claude-sonnet-4-6"; }
    @Override public boolean supportsBaseUrl() { return true; }

    @Override
    public Model createModel(ModelSpec spec) {
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        var builder = AnthropicChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(modelName);
        if (spec.hasBaseUrl()) {
            builder.baseUrl(spec.baseUrl());
        }
        return builder.build();
    }
}
