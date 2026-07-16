package io.pigagent.provider.gemini;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.gemini.GeminiChatModel;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;

/** Google Gemini protocol. */
public final class GeminiProtocol implements ModelProtocol {
    @Override public String protocolId() { return "gemini"; }
    @Override public String displayName() { return "Google Gemini"; }
    @Override public String description() { return "Google Gemini protocol (Gemini series)"; }
    @Override public String defaultModelName() { return "gemini-2.0-flash"; }

    @Override
    public Model createModel(ModelSpec spec) {
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        return GeminiChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(modelName)
                .build();
    }
}
