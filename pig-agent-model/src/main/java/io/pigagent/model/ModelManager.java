package io.pigagent.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.model.ModelErrorMessages;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;
import io.pigagent.core.agent.AgentModelSwitcher;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Global model management + runtime switching. Saves/loads model configs via {@link ModelStore},
 * builds AgentScope models through the matching {@link ModelProtocol}, runs a
 * connectivity probe before committing to a model, and rebuilds the live agent (through an
 * {@link AgentHolder} + {@link AgentFactory}) when the active model changes — without touching
 * conversation or memory. Implements {@link AgentModelSwitcher} so the session layer can request
 * the right model when activating a session.
 */
public final class ModelManager implements AgentModelSwitcher {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);

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

    /**
     * Resolve which stored model an agent's {@code modelId} refers to, falling back to the
     * default when the id is null or points at a model that no longer exists. Fault-tolerant:
     * never throws, so a dangling {@code modelId} degrades to the default instead of crashing.
     */
    public Optional<StoredModel> resolveStoredModel(String modelId) {
        if (modelId != null) {
            Optional<StoredModel> byId = store.findById(modelId);
            if (byId.isPresent()) {
                return byId;
            }
        }
        return getDefault();
    }

    /** Build the AgentScope model for an agent's {@code modelId}, with default fallback; null if
     * nothing is resolvable. Intended as the per-agent {@code ModelResolver} in the CLI wiring. */
    public Model modelFor(String modelId) {
        return resolveStoredModel(modelId).map(this::buildModel).orElse(null);
    }

    /** Build a concrete AgentScope model from a stored config via its provider. */
    public Model buildModel(StoredModel m) {
        ModelProtocol protocol = registry.findByProtocol(m.protocolId())
                .orElseThrow(() -> new IllegalStateException("Unknown protocol: " + m.protocolId()));
        return protocol.createModel(new ModelSpec(m.protocolId(), m.apiKey(), m.baseUrl(), m.modelName()));
    }

    /** Bounded wait for the connectivity probe so onboarding / {@code /model add} can't hang forever. */
    static final long PROBE_TIMEOUT_SECONDS = 25L;

    /**
     * Lightweight connectivity test: build the model and issue a tiny probe request, bounded by a
     * {@value #PROBE_TIMEOUT_SECONDS}s timeout. A failure returns a friendly, credential-redacted
     * message (shared taxonomy via {@link ModelErrorMessages}) rather than the raw exception text, so
     * every caller ({@code OnboardingWizard}, {@code /model add|edit}) displays the same clear reason.
     */
    public TestResult test(StoredModel m) {
        final Model model;
        try {
            model = buildModel(m);
        } catch (Exception e) {
            return TestResult.failure(ModelErrorMessages.friendly(e));
        }
        return runProbe(() -> {
            PigAgent probe = PigAgent.builder()
                    .name("probe")
                    .sysPrompt("Connectivity test. Reply with: OK")
                    .model(model)
                    .build();
            return probe.call(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text("ping").build()).build());
        }, PROBE_TIMEOUT_SECONDS);
    }

    /**
     * Run a connectivity probe on a daemon worker with a bounded timeout, mapping the outcome to a
     * {@link TestResult}: a reply → success; null → no-response; a timeout → a clear "连接超时" reason;
     * any other failure → the friendly {@link ModelErrorMessages} taxonomy. Package-private + injectable
     * probe/timeout so the timeout + mapping paths are unit-testable without touching the network.
     */
    static TestResult runProbe(Callable<Msg> probe, long timeoutSeconds) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pig-model-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Msg> future = exec.submit(probe);
            Msg reply = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return reply != null ? TestResult.success() : TestResult.failure("模型无响应，请稍后再试。");
        } catch (TimeoutException te) {
            return TestResult.failure("连接超时（检查 base URL / 网络）。");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return TestResult.failure(ModelErrorMessages.friendly(cause));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return TestResult.failure("连接测试被中断。");
        } finally {
            exec.shutdownNow();
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
            log.error("Failed to switch to model '{}': {}", target.label(), e.getMessage(), e);
        }
    }
}
