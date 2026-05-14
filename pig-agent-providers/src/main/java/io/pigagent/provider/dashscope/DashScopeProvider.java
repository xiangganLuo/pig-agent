package io.pigagent.provider.dashscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.DashScopeChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ProviderCredentials;
import java.util.List;

public final class DashScopeProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "dashscope"; }
    @Override public String displayName() { return "DashScope (Qwen)"; }
    @Override public String description() { return "Qwen series models by Alibaba Cloud"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("DASHSCOPE_API_KEY"); }
    @Override public String defaultModelName() { return "qwen-max"; }

    @Override
    public Model createModel(ProviderCredentials credentials) {
        return DashScopeChatModel.builder()
                .apiKey(credentials.getRequired("DASHSCOPE_API_KEY"))
                .modelName(defaultModelName())
                .build();
    }
}
