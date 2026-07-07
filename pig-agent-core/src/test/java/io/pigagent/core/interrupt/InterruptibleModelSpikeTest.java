package io.pigagent.core.interrupt;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group 1 Spike (interruptible-run): validate the fallback semantics of a cancellable model call —
 * on interrupt the current stream is disposed, the subscriber receives NO further signals, and the
 * stream terminates with {@link TurnInterruptedException} (an error, so the agent writes no
 * AGENT_RESULT to history). This is verified against a controllable in-memory source; the true
 * upstream-HTTP cancel behavior of each provider SDK is left for online human verification.
 */
class InterruptibleModelSpikeTest {

    /** A model whose stream is driven by a caller-controlled sink, so we can interleave interrupt. */
    private static Sinks.Many<ChatResponse> newSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    private static Model modelFrom(Sinks.Many<ChatResponse> sink) {
        return new Model() {
            @Override
            public String getModelName() {
                return "controllable";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return sink.asFlux();
            }
        };
    }

    private static ChatResponse chunk(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason(null).build();
    }

    private static List<Msg> msgs() {
        return List.of(Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build());
    }

    @Test
    void interrupt_disposesStream_noFurtherSignals_andErrorsWithInterrupt() {
        // Arrange — a live turn and a subscribed interruptible stream.
        InterruptController controller = new InterruptController();
        Sinks.Many<ChatResponse> upstream = newSink();
        InterruptibleModel model = new InterruptibleModel(modelFrom(upstream), controller);
        TurnHandle handle = controller.begin();

        List<String> received = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        Disposable sub = model.stream(msgs(), List.of(), null).subscribe(
                r -> received.add("chunk"),
                error::set,
                () -> completed.set(true));

        // Act — one chunk arrives, then the turn is interrupted, then a late chunk is pushed.
        upstream.tryEmitNext(chunk("A"));
        boolean fired = controller.interruptCurrent();
        upstream.tryEmitNext(chunk("B")); // must never reach the subscriber

        // Assert — interrupt fired, stream errored with TurnInterruptedException, B was dropped.
        assertThat(fired).isTrue();
        assertThat(received).hasSize(1); // only the pre-interrupt chunk
        assertThat(completed).isFalse(); // terminated by error, not normal completion
        assertThat(error.get()).isInstanceOf(TurnInterruptedException.class);
        assertThat(sub.isDisposed()).isTrue();
    }

    @Test
    void noInterrupt_streamCompletesNormally() {
        // Arrange
        InterruptController controller = new InterruptController();
        Sinks.Many<ChatResponse> upstream = newSink();
        InterruptibleModel model = new InterruptibleModel(modelFrom(upstream), controller);
        controller.begin();

        List<String> received = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        model.stream(msgs(), List.of(), null).subscribe(
                r -> received.add("chunk"), error::set, () -> completed.set(true));

        // Act — emit then complete the source, no interrupt.
        upstream.tryEmitNext(chunk("A"));
        upstream.tryEmitNext(chunk("B"));
        upstream.tryEmitComplete();

        // Assert — everything relayed, normal completion, no error.
        assertThat(received).hasSize(2);
        assertThat(completed).isTrue();
        assertThat(error.get()).isNull();
    }

    @Test
    void noActiveTurn_passesThroughDelegateUnchanged() {
        // Arrange — no controller.begin(): the decorator must behave as the bare model.
        InterruptController controller = new InterruptController();
        Sinks.Many<ChatResponse> upstream = newSink();
        InterruptibleModel model = new InterruptibleModel(modelFrom(upstream), controller);

        List<String> received = new CopyOnWriteArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        model.stream(msgs(), List.of(), null).subscribe(
                r -> received.add("chunk"), t -> { }, () -> completed.set(true));

        // Act
        upstream.tryEmitNext(chunk("A"));
        upstream.tryEmitComplete();

        // Assert — interruptCurrent is a no-op with no active turn.
        assertThat(controller.interruptCurrent()).isFalse();
        assertThat(received).hasSize(1);
        assertThat(completed).isTrue();
    }
}
