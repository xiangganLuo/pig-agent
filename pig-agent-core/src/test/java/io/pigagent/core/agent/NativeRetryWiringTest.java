package io.pigagent.core.agent;

import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.middleware.MiddlewareBase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 5a — native retry wiring. The self-built {@code RetryingModel}/{@code RetryPolicy}/
 * {@code TransientErrorClassifier} are deleted; retry is now native to AgentScope's model layer
 * (each extension {@code ChatModel} applies {@code ModelUtils.applyTimeoutAndRetry}, whose
 * {@code RETRYABLE_ERRORS} filter already distinguishes transient 429/5xx/timeout/IO from permanent
 * 4xx/auth). Because retry lives <em>inside</em> the real model impls (not a decorator around any
 * {@code Model}), it can't be exercised with a fake model offline — the real behavior is a live-model
 * IT. What we CAN assert offline is that pig's builder/factory wiring reaches the native
 * {@code ModelConfig} (maxRetries + fallbackModel) on the underlying {@code ReActAgent}.
 */
class NativeRetryWiringTest {

    /** A minimal no-op model — never invoked here (we inspect config, not run a turn). */
    private static Model dummyModel() {
        return new Model() {
            @Override
            public String getModelName() {
                return "dummy";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }
        };
    }

    @Test
    void maxRetries_reachesNativeModelConfig() {
        PigAgent agent = PigAgent.builder().name("t").sysPrompt("s")
                .model(dummyModel()).maxRetries(7).build();

        assertThat(agent.getReactAgent().getModelConfig().maxRetries()).isEqualTo(7);
    }

    @Test
    void noMaxRetries_keepsNativeDefault() {
        PigAgent agent = PigAgent.builder().name("t").sysPrompt("s")
                .model(dummyModel()).build();

        // Native default retry stays in place (not disabled) when pig does not set it.
        assertThat(agent.getReactAgent().getModelConfig().maxRetries())
                .isEqualTo(ModelConfig.DEFAULT_MAX_RETRIES);
    }

    @Test
    void fallbackModel_reachesNativeModelConfig() {
        Model fallback = dummyModel();
        PigAgent agent = PigAgent.builder().name("t").sysPrompt("s")
                .model(dummyModel()).fallbackModel(fallback).build();

        assertThat(agent.getReactAgent().getModelConfig().fallbackModel()).isSameAs(fallback);
    }

    @Test
    void agentFactory_propagatesMaxRetries() {
        AgentFactory factory = new AgentFactory("t", "s", null, List.<MiddlewareBase>of(), null, 5);

        PigAgent agent = factory.create(dummyModel());

        assertThat(agent.getReactAgent().getModelConfig().maxRetries()).isEqualTo(5);
    }
}
