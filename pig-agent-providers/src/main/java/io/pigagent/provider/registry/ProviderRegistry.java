package io.pigagent.provider.registry;

import io.pigagent.core.provider.AgentOnboardingProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ProviderRegistry {

    private final List<AgentOnboardingProvider> providers = new ArrayList<>();

    public void register(AgentOnboardingProvider provider) { providers.add(provider); }

    public Optional<AgentOnboardingProvider> findById(String providerId) {
        return providers.stream().filter(p -> p.providerId().equals(providerId)).findFirst();
    }

    public List<AgentOnboardingProvider> getAllProviders() { return List.copyOf(providers); }

    public List<AgentOnboardingProvider> getAvailableProviders() {
        return providers.stream().filter(AgentOnboardingProvider::isAvailable).toList();
    }
}
