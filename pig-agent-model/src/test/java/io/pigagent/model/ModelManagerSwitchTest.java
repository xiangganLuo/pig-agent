package io.pigagent.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.core.protocol.ModelSpec;
import io.pigagent.provider.registry.ProtocolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline coverage for {@link ModelManager}'s runtime switch path — {@code ensureModel} (switch,
 * no-op, default fallback, unknown target, build-failure-keeps-previous, not-attached) and
 * {@code attachChannel} (channel agent rebuilt alongside the main agent). A fake {@link
 * ModelProtocol} returns a stub {@link Model}, so nothing touches the network.
 */
class ModelManagerSwitchTest {

    private ProtocolRegistry registry;
    private ModelStore store;
    private AgentHolder holder;
    private AgentFactory factory;
    private PigAgent initialAgent;

    @BeforeEach
    void setUp() {
        registry = new ProtocolRegistry();
        registry.register(fakeProtocol("fake"));
        registry.register(throwingProtocol("boom"));
        store = mock(ModelStore.class);
        factory = new AgentFactory("a", "s", new Toolkit(), List.of(), null);
        initialAgent = factory.create(stubModel("initial"));
        holder = new AgentHolder(initialAgent);
    }

    private ModelManager attached(String currentModelId) {
        ModelManager mm = new ModelManager(registry, store);
        mm.attach(holder, factory, currentModelId);
        return mm;
    }

    private static StoredModel model(String id, String protocol) {
        return new StoredModel(id, protocol, "k", null, "m-" + id);
    }

    @Test
    void ensureModel_switchesToNewModel_rebuildsAgentAndUpdatesCurrent() {
        when(store.findById("m2")).thenReturn(Optional.of(model("m2", "fake")));
        ModelManager mm = attached("m1");

        mm.ensureModel("m2");

        assertThat(holder.get()).isNotSameAs(initialAgent); // agent rebuilt on the new model
        assertThat(mm.getCurrentModelId()).isEqualTo("m2");
    }

    @Test
    void ensureModel_withRegistry_rebuildsActiveInstanceThatKernelRuns() {
        // Regression for the "/model switch didn't take effect" bug: chat runs registry.active().agent(),
        // so a switch must rebuild the ACTIVE instance — not merely the holder mirror.
        when(store.findById("m2")).thenReturn(Optional.of(model("m2", "fake")));
        AgentRegistry reg = new AgentRegistry(holder);
        reg.register(new AgentInstance("default", AgentSpec.create("default", "d"), initialAgent));
        ModelManager mm = attached("m1");
        mm.attachRegistry(reg::replaceActiveAgent);

        mm.ensureModel("m2");

        assertThat(reg.active().orElseThrow().agent()).isNotSameAs(initialAgent); // what kernel.chat runs
        assertThat(holder.get()).isNotSameAs(initialAgent);                         // mirror followed
        assertThat(reg.active().orElseThrow().agent()).isSameAs(holder.get());      // consistent
        assertThat(mm.getCurrentModelId()).isEqualTo("m2");
    }

    @Test
    void ensureModel_sameAsCurrent_isNoOp() {
        when(store.findById("m1")).thenReturn(Optional.of(model("m1", "fake")));
        ModelManager mm = attached("m1");

        mm.ensureModel("m1");

        assertThat(holder.get()).isSameAs(initialAgent); // not rebuilt
        assertThat(mm.getCurrentModelId()).isEqualTo("m1");
    }

    @Test
    void ensureModel_nullId_usesDefault() {
        when(store.getDefaultId()).thenReturn("m2");
        when(store.findById("m2")).thenReturn(Optional.of(model("m2", "fake")));
        ModelManager mm = attached("m1");

        mm.ensureModel(null);

        assertThat(holder.get()).isNotSameAs(initialAgent);
        assertThat(mm.getCurrentModelId()).isEqualTo("m2");
    }

    @Test
    void ensureModel_unknownTarget_isNoOp() {
        when(store.findById("gone")).thenReturn(Optional.empty());
        when(store.getDefaultId()).thenReturn(null);
        ModelManager mm = attached("m1");

        mm.ensureModel("gone");

        assertThat(holder.get()).isSameAs(initialAgent);
        assertThat(mm.getCurrentModelId()).isEqualTo("m1");
    }

    @Test
    void ensureModel_buildFailure_keepsPreviousAgentAndModel() {
        // Target resolves, but its protocol throws while building the model.
        when(store.findById("bad")).thenReturn(Optional.of(model("bad", "boom")));
        ModelManager mm = attached("m1");

        mm.ensureModel("bad"); // must not throw

        assertThat(holder.get()).isSameAs(initialAgent); // previous agent preserved
        assertThat(mm.getCurrentModelId()).isEqualTo("m1"); // previous model preserved
    }

    @Test
    void ensureModel_notAttached_isNoOp() {
        ModelManager mm = new ModelManager(registry, store); // never attached

        mm.ensureModel("m2"); // must not throw

        assertThat(mm.getCurrentModelId()).isNull();
    }

    @Test
    void attachChannel_channelAgentRebuiltOnSwitch() {
        when(store.findById("m2")).thenReturn(Optional.of(model("m2", "fake")));
        ModelManager mm = attached("m1");
        AgentFactory channelFactory = new AgentFactory("c", "s", new Toolkit(), List.of(), null);
        PigAgent initialChannel = channelFactory.create(stubModel("channel-initial"));
        AgentHolder channelHolder = new AgentHolder(initialChannel);
        mm.attachChannel(channelHolder, channelFactory);

        mm.ensureModel("m2");

        assertThat(holder.get()).isNotSameAs(initialAgent);
        assertThat(channelHolder.get()).isNotSameAs(initialChannel); // channel followed the switch
    }

    @Test
    void modelFor_buildsModelViaProtocol_withDefaultFallback() {
        when(store.findById("m2")).thenReturn(Optional.of(model("m2", "fake")));
        ModelManager mm = attached("m1");

        Model built = mm.modelFor("m2");

        assertThat(built).isNotNull();
        assertThat(built.getModelName()).isEqualTo("m-m2");
    }

    // --- stubs ---------------------------------------------------------------

    private static Model stubModel(String name) {
        return new Model() {
            @Override
            public String getModelName() {
                return name;
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return Flux.empty();
            }
        };
    }

    private static ModelProtocol fakeProtocol(String id) {
        return new ModelProtocol() {
            @Override
            public String protocolId() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public String description() {
                return id;
            }

            @Override
            public String defaultModelName() {
                return "m";
            }

            @Override
            public Model createModel(ModelSpec spec) {
                return stubModel(spec.modelName());
            }
        };
    }

    private static ModelProtocol throwingProtocol(String id) {
        return new ModelProtocol() {
            @Override
            public String protocolId() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public String description() {
                return id;
            }

            @Override
            public String defaultModelName() {
                return "m";
            }

            @Override
            public Model createModel(ModelSpec spec) {
                throw new IllegalStateException("boom");
            }
        };
    }
}
