package io.pigagent.provider.mimo;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ProviderCredentials;

import java.util.List;

public final class MimoProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "mimo"; }
    @Override public String displayName() { return "mimo"; }
    @Override public String description() { return "mimo series models by xiaomi"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("MIMO_API_KEY"); }
    @Override public String defaultModelName() { return "mimo-v2.5-pro"; }

    @Override
    public Model createModel(ProviderCredentials credentials) {
        return OpenAIChatModel.builder()
                .apiKey(credentials.getRequired("MIMO_API_KEY"))
                .baseUrl("https://token-plan-cn.xiaomimimo.com/v1")
                .modelName(defaultModelName())
                .build();
    }
}
