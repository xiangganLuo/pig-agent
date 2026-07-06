package io.pigagent.core.retry;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end (offline) wiring: an agent built via {@link AgentFactory} with a retry policy wraps
 * its model in a {@link RetryingModel}. A transient model failure is retried <em>inside</em> the
 * single agent invocation, so the real {@code ReActAgent} completes normally and is never
 * re-entered ("Agent is still running").
 */
class ModelRetryWiringTest {

    static final class FakeModel implements Model {
        final AtomicInteger calls = new AtomicInteger();
        private final int failFirst;
        private final String errorMessage;

        FakeModel(int failFirst, String errorMessage) {
            this.failFirst = failFirst;
            this.errorMessage = errorMessage;
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int n = calls.incrementAndGet();
            if (n <= failFirst) {
                return Flux.error(new RuntimeException(errorMessage));
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("hello from fake").build()))
                    .finishReason("stop").build());
        }
    }

    private AgentFactory factory(int maxRetries) {
        RetryPolicy policy = new RetryPolicy(true, maxRetries, Duration.ZERO,
                Duration.ofMillis(1), Duration.ofMillis(1), new TransientErrorClassifier(), null);
        return new AgentFactory("t", "s", null, List.<Hook>of(), null, policy);
    }

    private Msg userMsg() {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build();
    }

    @Test
    void transientFailures_retriedUnderneath_agentCompletesNormally() {
        FakeModel model = new FakeModel(2, "502 upstream_error");
        PigAgent agent = factory(5).create(model);

        List<Event> events = agent.stream(userMsg()).collectList().block();

        assertThat(model.calls.get()).isGreaterThanOrEqualTo(3); // retried under the agent
        String text = events == null ? "" : events.stream()
                .filter(e -> e.getType() == EventType.AGENT_RESULT)
                .map(e -> e.getMessage().getTextContent())
                .reduce("", (a, b) -> a + b);
        assertThat(text).contains("hello from fake");
    }

    @Test
    void permanentFailure_notRetried() {
        FakeModel model = new FakeModel(Integer.MAX_VALUE, "401 Unauthorized");
        PigAgent agent = factory(5).create(model);

        assertThatThrownBy(() -> agent.stream(userMsg()).blockLast()).isInstanceOf(Exception.class);
        assertThat(model.calls.get()).isEqualTo(1);
    }
}
