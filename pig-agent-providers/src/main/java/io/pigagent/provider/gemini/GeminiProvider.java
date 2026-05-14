package io.pigagent.provider.gemini;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.GeminiChatModel;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.core.provider.ProviderCredentials;
import java.util.List;

public final class GeminiProvider implements AgentOnboardingProvider {
    @Override public String providerId() { return "gemini"; }
    @Override public String displayName() { return "Google Gemini"; }
    @Override public String description() { return "Gemini series models by Google"; }
    @Override public List<String> requiredCredentialKeys() { return List.of("GEMINI_API_KEY"); }
    @Override public String defaultModelName() { return "gemini-2.0-flash"; }

    @Override
    public Model createModel(ProviderCredentials credentials) {
        return GeminiChatModel.builder()
                .apiKey(credentials.getRequired("GEMINI_API_KEY"))
                .modelName(defaultModelName())
                .build();
    }
}
