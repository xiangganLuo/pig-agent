package io.pigagent.onboarding;

import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.provider.registry.ProviderRegistry;
import io.pigagent.workspace.WorkspaceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public final class OnboardingWizard {

    private final ProviderRegistry providerRegistry;
    private final ConfigurationManager configManager;
    private final WorkspaceManager workspaceManager;
    private final BufferedReader reader;

    public OnboardingWizard(ProviderRegistry providerRegistry,
                            ConfigurationManager configManager,
                            WorkspaceManager workspaceManager) {
        this.providerRegistry = providerRegistry;
        this.configManager = configManager;
        this.workspaceManager = workspaceManager;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    public void run() throws IOException {
        System.out.println("=== Pig Agent Setup ===\n");
        AgentOnboardingProvider selectedProvider = selectProvider();
        configureCredentials(selectedProvider);
        configManager.updateConfig(config -> {
            config.getModel().setProvider(selectedProvider.providerId());
            config.getModel().setModelName(selectedProvider.defaultModelName());
        });
        System.out.println("\nSetup complete! Starting Pig Agent...\n");
    }

    private AgentOnboardingProvider selectProvider() throws IOException {
        List<AgentOnboardingProvider> providers = providerRegistry.getAllProviders();
        System.out.println("Available LLM providers:");
        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            String available = p.isAvailable() ? " [credentials found]" : "";
            System.out.printf("  %d) %s - %s%s%n", i + 1, p.displayName(), p.description(), available);
        }

        // Auto-select first available provider if stdin is not available
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).isAvailable()) {
                System.out.printf("\nAuto-selected: %s (credentials found)%n", providers.get(i).displayName());
                return providers.get(i);
            }
        }

        System.out.print("\nSelect provider (number): ");
        String input = reader.readLine();
        if (input == null || input.isBlank()) {
            System.out.println("No input, defaulting to first provider.");
            return providers.get(0);
        }
        int idx = Integer.parseInt(input.trim()) - 1;
        return providers.get(idx);
    }

    private void configureCredentials(AgentOnboardingProvider provider) throws IOException {
        List<String> keys = provider.requiredCredentialKeys();
        if (keys.isEmpty()) {
            System.out.println("No credentials needed for " + provider.displayName());
            return;
        }
        System.out.println("\nCredential configuration for " + provider.displayName() + ":");
        for (String key : keys) {
            String existing = System.getenv(key);
            if (existing != null && !existing.isBlank()) {
                System.out.printf("  %s: [already set in environment]%n", key);
            } else {
                System.out.printf("  %s: Set as environment variable before starting.%n", key);
            }
        }
    }
}
