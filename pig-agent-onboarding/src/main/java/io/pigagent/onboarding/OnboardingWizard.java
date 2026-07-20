package io.pigagent.onboarding;

import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.model.ModelKind;
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
                offerEmbeddingModel();
                return;
            } catch (EndOfInputException e) {
                throw new IOException("Onboarding requires interactive input (no stdin available).");
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage() + "\n");
            }
        }
    }

    /**
     * Optional, skippable embedding-model step (capability {@code embedding-model-layer}). The chat
     * model above is mandatory; this offers to also configure an OpenAI-compatible {@code /embeddings}
     * endpoint for memory search. Declining (or any failure here) leaves the setup with the chat model
     * only — byte-identical to before this step existed (BM25-only memory search). Never fatal.
     */
    private void offerEmbeddingModel() {
        try {
            String ans = readLine("Configure an embedding model for memory search? (optional, y/N): ").trim();
            if (!ans.equalsIgnoreCase("y")) {
                return; // skip → chat-only, today's behavior
            }
            ModelProtocol protocol = selectProtocol();
            String apiKey = promptApiKey(protocol);
            String baseUrl = promptBaseUrl(protocol);
            String modelName = promptEmbeddingModelName();
            StoredModel emb = StoredModel.create(protocol.protocolId(), apiKey,
                    baseUrl == null || baseUrl.isBlank() ? null : baseUrl, modelName, ModelKind.EMBEDDING);

            System.out.println("\nTesting embeddings endpoint " + emb.label() + " ...");
            ModelManager.TestResult result = tryAddEmbeddingModel(modelManager, emb);
            if (result.ok()) {
                System.out.println("Success! Saved '" + emb.label() + "' as the default embedding model.\n");
            } else {
                System.out.println("Embeddings test failed: " + result.error()
                        + " (not saved). Continuing without an embedding model.\n");
            }
        } catch (RuntimeException | IOException e) {
            // Optional step: any error (bad input, closed stdin) simply skips it — chat model is saved.
            System.out.println("Skipping embedding model setup.\n");
        }
    }

    /**
     * Test an embedding model and, on success, persist it and make it the default embedding model when
     * none is set yet (mirrors the default-chat rule). Package-private + {@link ModelManager}-driven so
     * the persistence decision is unit-testable offline (the {@code /embeddings} round-trip is stubbed).
     */
    static ModelManager.TestResult tryAddEmbeddingModel(ModelManager mm, StoredModel emb) {
        ModelManager.TestResult result = mm.test(emb);
        if (result.ok()) {
            mm.add(emb);
            if (mm.getDefaultEmbeddingModelId() == null) {
                mm.setDefaultEmbeddingModelId(emb.id());
            }
        }
        return result;
    }

    private String promptEmbeddingModelName() throws IOException {
        while (true) {
            String name = readLine("Embedding model name (e.g. text-embedding-3-small): ").trim();
            if (!name.isBlank()) {
                return name;
            }
            System.out.println("Embedding model name is required.");
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
