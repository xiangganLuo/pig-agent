package io.pigagent.core.retry;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end (offline, deterministic) wiring test: a fake {@link Model} drives the real
 * {@link PigAgent#stream} path so we prove the {@link RetryPolicy} re-subscribes down to the
 * model on transient failures and gives up on permanent ones — without any live LLM.
 */
class ModelRetryWiringTest {

    /** Model that fails transiently the first {@code failFirst} calls, then returns text. */
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
            ChatResponse resp = ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("hello from fake").build()))
                    .finishReason("stop")
                    .build();
            return Flux.just(resp);
        }
    }

    private RetryPolicy policy(int maxRetries) {
        return new RetryPolicy(true, maxRetries, Duration.ofSeconds(30),
                Duration.ofMillis(1), Duration.ofMillis(1),
                new TransientErrorClassifier(), null);
    }

    private Msg userMsg() {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build();
    }

    @Test
    void transientFailures_areRetried_untilModelSucceeds() {
        // Arrange — fail twice (502) then succeed.
        FakeModel model = new FakeModel(2, "502 upstream_error");
        PigAgent agent = PigAgent.builder()
                .name("t").sysPrompt("s").model(model).retryPolicy(policy(5)).build();

        // Act — consume the stream to completion.
        List<Event> events = agent.stream(userMsg()).collectList().block();

        // Assert — the model was re-invoked past its failures and a result came through.
        assertThat(model.calls.get()).isGreaterThanOrEqualTo(3); // 2 failures + at least one success
        String text = events == null ? "" : events.stream()
                .filter(e -> e.getType() == EventType.AGENT_RESULT)
                .map(e -> e.getMessage().getTextContent())
                .reduce("", (a, b) -> a + b);
        assertThat(text).contains("hello from fake");
    }

    @Test
    void permanentFailure_isNotRetried() {
        // Arrange — always 401 (permanent).
        FakeModel model = new FakeModel(Integer.MAX_VALUE, "401 Unauthorized");
        PigAgent agent = PigAgent.builder()
                .name("t").sysPrompt("s").model(model).retryPolicy(policy(5)).build();

        // Act + Assert — fails fast, model called exactly once (no retry).
        assertThatThrownBy(() -> agent.stream(userMsg()).blockLast()).isInstanceOf(Exception.class);
        assertThat(model.calls.get()).isEqualTo(1);
    }
}
