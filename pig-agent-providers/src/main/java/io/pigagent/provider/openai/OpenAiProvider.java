package io.pigagent.provider.openai;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ProviderCredentials;
import java.util.List;

public final class OpenAiProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "openai"; }
    @Override public String displayName() { return "OpenAI (GPT)"; }
    @Override public String description() { return "GPT series models by OpenAI"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("OPENAI_API_KEY"); }
    @Override public String defaultModelName() { return "gpt-4o"; }

    @Override
    public Model createModel(ProviderCredentials credentials) {
        return OpenAIChatModel.builder()
                .apiKey(credentials.getRequired("OPENAI_API_KEY"))
                .modelName(defaultModelName())
                .build();
    }
}
