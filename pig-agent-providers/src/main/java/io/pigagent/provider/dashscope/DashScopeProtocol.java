package io.pigagent.provider.dashscope;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;

/** DashScope native protocol (Alibaba Cloud, Qwen series). */
public final class DashScopeProtocol implements ModelProtocol {
    @Override public String protocolId() { return "dashscope"; }
    @Override public String displayName() { return "DashScope (Qwen)"; }
    @Override public String description() { return "Alibaba Cloud DashScope native protocol (Qwen series)"; }
    @Override public String defaultModelName() { return "qwen-max"; }

    @Override
    public Model createModel(ModelSpec spec) {
        String modelName = spec.modelName() == null || spec.modelName().isBlank()
                ? defaultModelName() : spec.modelName();
        return DashScopeChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(modelName)
                .build();
    }
}
