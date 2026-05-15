package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * The central Pig Agent, wrapping AgentScope's ReActAgent.
 * Delegates to the underlying ReActAgent for reasoning and tool calling.
 */
public final class PigAgent {

    private final ReActAgent reactAgent;
    private final String agentName;
    private final Model model;

    private PigAgent(ReActAgent reactAgent, String agentName, Model model) {
        this.reactAgent = Objects.requireNonNull(reactAgent, "reactAgent");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.model = Objects.requireNonNull(model, "model");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Msg call(Msg userMsg) {
        return reactAgent.call(userMsg).block();
    }

    public Flux<io.agentscope.core.agent.Event> stream(Msg userMsg) {
        return reactAgent.stream(userMsg);
    }

    public String getAgentName() {
        return agentName;
    }

    public Model getModel() {
        return model;
    }

    public ReActAgent getReactAgent() {
        return reactAgent;
    }

    public static final class Builder {
        private String name = "PigAgent";
        private String sysPrompt = "You are a helpful AI assistant.";
        private Model model;
        private Toolkit toolkit;
        private List<io.agentscope.core.hook.Hook> hooks;
        private LongTermMemory longTermMemory;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder hooks(List<io.agentscope.core.hook.Hook> hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        public PigAgent build() {
            Objects.requireNonNull(model, "model must be set before building");

            ReActAgent.Builder reactBuilder = ReActAgent.builder()
                    .name(name)
                    .sysPrompt(sysPrompt)
                    .model(model)
                    .memory(new InMemoryMemory());

            if (longTermMemory != null) {
                reactBuilder.longTermMemory(longTermMemory);
                reactBuilder.longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL);
            }

            if (toolkit != null) {
                reactBuilder.toolkit(toolkit);
            }
            if (hooks != null && !hooks.isEmpty()) {
                reactBuilder.hooks(hooks);
            }

            ReActAgent reactAgent = reactBuilder.build();
            return new PigAgent(reactAgent, name, model);
        }
    }
}
