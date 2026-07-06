package io.pigagent.core.retry;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry happens at the Model layer: {@link RetryingModel} re-invokes the delegate's
 * {@code stream} on transient failures, so the wrapping agent never sees a re-subscription.
 */
class RetryingModelTest {

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
                    .content(List.of(TextBlock.builder().text("ok").build()))
                    .finishReason("stop").build());
        }
    }

    private RetryPolicy policy(int maxRetries) {
        return new RetryPolicy(true, maxRetries, Duration.ZERO,
                Duration.ofMillis(1), Duration.ofMillis(1), new TransientErrorClassifier(), null);
    }

    private static List<Msg> msgs() {
        return List.of(Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build());
    }

    @Test
    void getModelName_delegates() {
        assertThat(new RetryingModel(new FakeModel(0, "x"), policy(3)).getModelName()).isEqualTo("fake");
    }

    @Test
    void transientFailures_retriedUntilDelegateSucceeds() {
        FakeModel fake = new FakeModel(2, "502 upstream_error");
        RetryingModel model = new RetryingModel(fake, policy(5));

        List<ChatResponse> out = model.stream(msgs(), List.of(), null).collectList().block();

        assertThat(fake.calls.get()).isEqualTo(3); // 2 failures + 1 success — delegate re-invoked
        assertThat(out).hasSize(1);
    }

    @Test
    void permanentFailure_notRetried() {
        FakeModel fake = new FakeModel(Integer.MAX_VALUE, "401 Unauthorized");
        RetryingModel model = new RetryingModel(fake, policy(5));

        assertThatThrownBy(() -> model.stream(msgs(), List.of(), null).blockLast())
                .isInstanceOf(Exception.class);
        assertThat(fake.calls.get()).isEqualTo(1);
    }
}
