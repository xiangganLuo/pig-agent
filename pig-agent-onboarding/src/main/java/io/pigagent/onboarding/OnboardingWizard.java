package io.pigagent.onboarding;

import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.provider.registry.ProtocolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * First-run model setup. Interactively guides the user to pick a protocol, enter the API key
 * (and optional base URL / model version), runs a connectivity test, and saves the result as
 * the global default model. There is no way to skip — the loop repeats until one model is
 * successfully configured, and no partial config is saved on failure.
 */
public final class OnboardingWizard {

    private final ProtocolRegistry protocolRegistry;
    private final ModelManager modelManager;
    private final BufferedReader reader;
    private boolean warnedNoMask;

    public OnboardingWizard(ProtocolRegistry protocolRegistry, ModelManager modelManager) {
        this.protocolRegistry = protocolRegistry;
        this.modelManager = modelManager;
        this.reader = new BufferedReader(new InputStreamReader(System.in));
    }

    public void run() throws IOException {
        System.out.println("=== Pig Agent Setup ===");
        System.out.println("A large language model must be configured before the agent can be used.\n");

        while (true) {
            try {
                ModelProtocol protocol = selectProtocol();
                String apiKey = promptApiKey(protocol);
                String baseUrl = promptBaseUrl(protocol);
                String modelName = promptModelName(protocol);

                StoredModel model = StoredModel.create(protocol.protocolId(), apiKey,
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

    private ModelProtocol selectProtocol() throws IOException {
        List<ModelProtocol> protocols = protocolRegistry.getAllProtocols();
        System.out.println("Select a model protocol:");
        for (int i = 0; i < protocols.size(); i++) {
            ModelProtocol p = protocols.get(i);
            System.out.printf("  %d) %-18s %s%n", i + 1, p.displayName(), p.description());
        }
        String input = readLine("Protocol number: ");
        int idx;
        try {
            idx = Integer.parseInt(input.trim()) - 1;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please enter a valid number.");
        }
        if (idx < 0 || idx >= protocols.size()) {
            throw new IllegalArgumentException("Number out of range.");
        }
        return protocols.get(idx);
    }

    private String promptApiKey(ModelProtocol protocol) throws IOException {
        if (!protocol.requiresApiKey()) {
            return null;
        }
        while (true) {
            String key = readSecret("API key for " + protocol.displayName() + ": ").trim();
            if (!key.isBlank()) {
                return key;
            }
            System.out.println("API key is required.");
        }
    }

    /**
     * Reads a secret without echoing it. Uses {@link java.io.Console#readPassword} when a console is
     * available; when it is not (piped stdin / IDE / {@code mvn exec}), falls back to visible reading
     * with a one-time warning — masking is impossible without a console, but onboarding must proceed.
     */
    private String readSecret(String prompt) throws IOException {
        java.io.Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            if (chars == null) {
                throw new EndOfInputException();
            }
            return new String(chars);
        }
        if (!warnedNoMask) {
            System.out.println("[warn] No interactive console — the API key will be visible as you type.");
            warnedNoMask = true;
        }
        return readLine(prompt);
    }

    private String promptBaseUrl(ModelProtocol protocol) throws IOException {
        if (!protocol.supportsBaseUrl()) {
            return null;
        }
        return readLine("Custom base URL / endpoint (optional, press Enter to skip): ").trim();
    }

    private String promptModelName(ModelProtocol protocol) throws IOException {
        String def = protocol.defaultModelName();
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
