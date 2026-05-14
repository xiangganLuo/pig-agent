package io.pigagent.provider.anthropic;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.AnthropicChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ProviderCredentials;
import java.util.List;

public final class AnthropicProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "anthropic"; }
    @Override public String displayName() { return "Anthropic (Claude)"; }
    @Override public String description() { return "Claude series models by Anthropic"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("ANTHROPIC_API_KEY"); }
    @Override public String defaultModelName() { return "mimo-v2.5-pro"; }

    @Override
    public Model createModel(ProviderCredentials credentials) {
        return AnthropicChatModel.builder()
                .apiKey(credentials.getRequired("ANTHROPIC_API_KEY"))
                .modelName(defaultModelName())
                .build();
    }
}
