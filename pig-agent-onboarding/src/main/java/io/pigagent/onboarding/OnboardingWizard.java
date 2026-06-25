package io.pigagent.onboarding;

import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.registry.ProviderRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * First-run model setup. Interactively guides the user to pick a provider, enter the API key
 * (and optional base URL / model version), runs a connectivity test, and saves the result as
 * the global default model. There is no way to skip — the loop repeats until one model is
 * successfully configured, and no partial config is saved on failure.
 */
public final class OnboardingWizard {

    private final ProviderRegistry providerRegistry;
    private final ModelManager modelManager;
    private final BufferedReader reader;

    public OnboardingWizard(ProviderRegistry providerRegistry, ModelManager modelManager) {
        this.providerRegistry = providerRegistry;
        this.modelManager = modelManager;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    public void run() throws IOException {
        System.out.println("=== Pig Agent Setup ===");
        System.out.println("A large language model must be configured before the agent can be used.\n");

        while (true) {
            try {
                AgentOnboardingProvider provider = selectProvider();
                String apiKey = promptApiKey(provider);
                String baseUrl = promptBaseUrl(provider);
                String modelName = promptModelName(provider);

                StoredModel model = StoredModel.create(provider.providerId(), apiKey,
                        baseUrl == null || baseUrl.isBlank() ? null : baseUrl, modelName);

                System.out.println("\nTesting connection to " + model.label() + " ...");
                ModelManager.TestResult result = modelManager.test(model);
                if (!result.ok()) {
                    System.out.println("Connection failed: " + result.error());
                    System.out.println("Please check the key / endpoint / model name and try again.\n");
                    continue; // no partial config saved; re-prompt
                }

                modelManager.add(model);
                modelManager.setDefault(model.id());
                System.out.println("\nSuccess! Saved '" + model.label() + "' as the default model.\n");
                return;
            } catch (EndOfInputException e) {
                throw new IOException("Onboarding requires interactive input (no stdin available).");
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage() + "\n");
            }
        }
    }

    private AgentOnboardingProvider selectProvider() throws IOException {
        List<AgentOnboardingProvider> providers = providerRegistry.getAllProviders();
        System.out.println("Select a model provider:");
        for (int i = 0; i < providers.size(); i++) {
            AgentOnboardingProvider p = providers.get(i);
            System.out.printf("  %d) %-18s %s%n", i + 1, p.displayName(), p.description());
        }
        String input = readLine("Provider number: ");
        int idx;
        try {
            idx = Integer.parseInt(input.trim()) - 1;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please enter a valid number.");
        }
        if (idx < 0 || idx >= providers.size()) {
            throw new IllegalArgumentException("Number out of range.");
        }
        return providers.get(idx);
    }

    private String promptApiKey(AgentOnboardingProvider provider) throws IOException {
        if (!provider.requiresApiKey()) {
            return null;
        }
        while (true) {
            String key = readLine("API key for " + provider.displayName() + ": ").trim();
            if (!key.isBlank()) {
                return key;
            }
            System.out.println("API key is required.");
        }
    }

    private String promptBaseUrl(AgentOnboardingProvider provider) throws IOException {
        if (!provider.supportsBaseUrl()) {
            return null;
        }
        return readLine("Custom base URL / endpoint (optional, press Enter to skip): ").trim();
    }

    private String promptModelName(AgentOnboardingProvider provider) throws IOException {
        String def = provider.defaultModelName();
        String input = readLine("Model name [" + def + "]: ").trim();
        return input.isBlank() ? def : input;
    }

    private String readLine(String prompt) throws IOException {
        System.out.print(prompt);
        String line = reader.readLine();
        if (line == null) {
            throw new EndOfInputException();
        }
        return line;
    }

    /** Signals stdin closed (cannot continue onboarding). */
    private static final class EndOfInputException extends RuntimeException {
    }
}
