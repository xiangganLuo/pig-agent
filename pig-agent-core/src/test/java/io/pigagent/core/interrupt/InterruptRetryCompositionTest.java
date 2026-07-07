package io.pigagent.core.interrupt;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.retry.RetryPolicy;
import io.pigagent.core.retry.RetryingModel;
import io.pigagent.core.retry.TransientErrorClassifier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group 2.3: interrupt/cancel is per single attempt (inner {@link InterruptibleModel}), retry is
 * the outer layer ({@link RetryingModel}). An interrupt surfaces as a non-transient
 * {@link TurnInterruptedException}, so the retry policy MUST NOT re-attempt it — the whole turn ends.
 */
class InterruptRetryCompositionTest {

    private static List<Msg> msgs() {
        return List.of(Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build());
    }

    private static ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason(null).build();
    }

    private RetryPolicy retryPolicy(int maxRetries) {
        return new RetryPolicy(true, maxRetries, Duration.ZERO,
                Duration.ofMillis(1), Duration.ofMillis(1), new TransientErrorClassifier(), null);
    }

    @Test
    void interruptWithinRetryChain_isNotRetried_endsTurn() {
        // Arrange — RetryingModel(InterruptibleModel(controllable)); count attempts.
        InterruptController controller = new InterruptController();
        Sinks.Many<ChatResponse> upstream = Sinks.many().multicast().onBackpressureBuffer();
        AtomicInteger attempts = new AtomicInteger();
        Model base = new Model() {
            @Override
            public String getModelName() {
                return "base";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                attempts.incrementAndGet();
                return upstream.asFlux();
            }
        };
        Model chain = new RetryingModel(new InterruptibleModel(base, controller), retryPolicy(5));
        controller.begin();

        List<String> received = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        chain.stream(msgs(), List.of(), null).subscribe(r -> received.add("chunk"), error::set, () -> { });

        // Act — one chunk, then interrupt.
        upstream.tryEmitNext(chunk("A"));
        controller.interruptCurrent();

        // Assert — interrupt ends the turn: no retry (single attempt), errored with interrupt.
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(error.get()).isInstanceOf(TurnInterruptedException.class);
        assertThat(received).hasSize(1);
    }

    @Test
    void transientErrorWithinChain_stillRetried_thenInterruptibleSucceeds() {
        // Arrange — first attempt fails 502 (transient), second attempt succeeds. Interrupt wrapper
        // must be transparent to the retry of a genuine transient failure.
        InterruptController controller = new InterruptController();
        AtomicInteger attempts = new AtomicInteger();
        Model base = new Model() {
            @Override
            public String getModelName() {
                return "base";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                if (attempts.incrementAndGet() == 1) {
                    return Flux.error(new RuntimeException("502 upstream_error"));
                }
                return Flux.just(chunk("ok"));
            }
        };
        Model chain = new RetryingModel(new InterruptibleModel(base, controller), retryPolicy(5));
        controller.begin();

        List<ChatResponse> out = chain.stream(msgs(), List.of(), null).collectList().block();

        assertThat(attempts.get()).isEqualTo(2); // retried once under the interrupt wrapper
        assertThat(out).hasSize(1);
    }
}
