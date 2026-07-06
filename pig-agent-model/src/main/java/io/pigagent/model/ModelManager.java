package io.pigagent.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.AgentModelSwitcher;

import java.util.List;
import java.util.Optional;

/**
 * Global model management + runtime switching. Saves/loads model configs via {@link ModelStore},
 * builds AgentScope models through the matching {@link ModelProtocol}, runs a
 * connectivity probe before committing to a model, and rebuilds the live agent (through an
 * {@link AgentHolder} + {@link AgentFactory}) when the active model changes — without touching
 * conversation or memory. Implements {@link AgentModelSwitcher} so the session layer can request
 * the right model when activating a session.
 */
public final class ModelManager implements AgentModelSwitcher {

    /** Result of a connectivity test. */
    public record TestResult(boolean ok, String error) {
        public static TestResult success() {
            return new TestResult(true, null);
        }

        public static TestResult failure(String error) {
            return new TestResult(false, error);
        }
    }

    private final ProtocolRegistry registry;
    private final ModelStore store;

    private AgentHolder holder;
    private AgentFactory factory;
    private String currentModelId;

    // Optional secondary agent for non-interactive channels (its own permission hook /
    // conversation), rebuilt on model switch alongside the main agent so channels also follow it.
    private AgentHolder channelHolder;
    private AgentFactory channelFactory;

    public ModelManager(ProtocolRegistry registry, ModelStore store) {
        this.registry = registry;
        this.store = store;
    }

    /** Connect the live agent machinery after the initial agent has been built. */
    public void attach(AgentHolder holder, AgentFactory factory, String currentModelId) {
        this.holder = holder;
        this.factory = factory;
        this.currentModelId = currentModelId;
    }

    /** Register a separate channel agent so model switches rebuild it too (optional). */
    public void attachChannel(AgentHolder channelHolder, AgentFactory channelFactory) {
        this.channelHolder = channelHolder;
        this.channelFactory = channelFactory;
    }

    /** True once at least one model is saved and a resolvable default exists. */
    public boolean isConfigured() {
        return getDefault().map(m -> registry.findByProtocol(m.protocolId()).isPresent()).orElse(false);
    }

    public List<StoredModel> list() {
        return store.findAll();
    }

    public Optional<StoredModel> findById(String id) {
        return store.findById(id);
    }

    public String getDefaultId() {
        return store.getDefaultId();
    }

    public Optional<StoredModel> getDefault() {
        String id = store.getDefaultId();
        return id == null ? Optional.empty() : store.findById(id);
    }

    public String getCurrentModelId() {
        return currentModelId;
    }

    public Optional<StoredModel> getCurrentModel() {
        return currentModelId == null ? Optional.empty() : store.findById(currentModelId);
    }

    public StoredModel add(StoredModel model) {
        return store.save(model);
    }

    public StoredModel edit(StoredModel model) {
        return store.save(model);
    }

    public void delete(String id) {
        store.deleteById(id);
    }

    public void setDefault(String id) {
        store.setDefaultId(id);
    }

    /** Build a concrete AgentScope model from a stored config via its provider. */
    public Model buildModel(StoredModel m) {
        ModelProtocol protocol = registry.findByProtocol(m.protocolId())
                .orElseThrow(() -> new IllegalStateException("Unknown protocol: " + m.protocolId()));
        return protocol.createModel(new ModelSpec(m.protocolId(), m.apiKey(), m.baseUrl(), m.modelName()));
    }

    /** Lightweight connectivity test: build the model and issue a tiny probe request. */
    public TestResult test(StoredModel m) {
        try {
            Model model = buildModel(m);
            PigAgent probe = PigAgent.builder()
                    .name("probe")
                    .sysPrompt("Connectivity test. Reply with: OK")
                    .model(model)
                    .build();
            Msg reply = probe.call(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text("ping").build()).build());
            return reply != null ? TestResult.success() : TestResult.failure("No response from model");
        } catch (Exception e) {
            return TestResult.failure(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @Override
    public void ensureModel(String modelId) {
        if (holder == null || factory == null) {
            return; // not attached yet
        }
        StoredModel target = (modelId != null ? store.findById(modelId) : getDefault()).orElse(null);
        if (target == null || target.id().equals(currentModelId)) {
            return; // nothing usable to switch to, or already active
        }
        try {
            Model model = buildModel(target);
            holder.set(factory.create(model));
            if (channelHolder != null && channelFactory != null) {
                channelHolder.set(channelFactory.create(model));
            }
            currentModelId = target.id();
        } catch (Exception e) {
            // Keep the previous model and conversation intact (§八).
            System.err.println("[Model] Failed to switch to '" + target.label() + "': " + e.getMessage());
        }
    }
}
