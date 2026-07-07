package io.pigagent.core.interrupt;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * A {@link Model} decorator that makes a single model call cancellable. On each subscription it
 * reads the {@link InterruptController}'s current {@link TurnHandle}; if a turn is active it relays
 * the delegate's stream until the turn's interrupt fires, at which point the delegate subscription
 * is cancelled and the stream errors with {@link TurnInterruptedException}.
 *
 * <p><b>Layering (design D1/D6):</b> this sits <em>inside</em> {@link
 * io.pigagent.core.retry.RetryingModel} — interrupt/cancel is per single attempt, retry is the
 * outer layer. Because an interrupt surfaces as a non-transient {@link TurnInterruptedException},
 * the retry policy does not re-attempt it, so an interrupt ends the whole turn.
 *
 * <p><b>Why error, not complete (D4):</b> using {@link Flux#takeUntilOther(org.reactivestreams.Publisher)}
 * with a companion that errors on interrupt makes the model stream error rather than complete
 * gracefully. The {@code ReActAgent} invocation therefore errors and writes no {@code AGENT_RESULT},
 * so no half-finished output pollutes the conversation history.
 *
 * <p>When no turn is registered (e.g. the {@code /model test} probe or unit tests that don't drive
 * the kernel), the delegate stream is returned unchanged — behavior is identical to the bare model.
 */
public final class InterruptibleModel implements Model {

    private final Model delegate;
    private final InterruptController controller;

    public InterruptibleModel(Model delegate, InterruptController controller) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            Flux<ChatResponse> body = delegate.stream(messages, tools, options);
            return controller.currentTurn()
                    .map(handle -> body.takeUntilOther(abortSignal(handle)))
                    .orElse(body);
        });
    }

    /** A companion that never emits, but errors with {@link TurnInterruptedException} on interrupt. */
    private static Mono<ChatResponse> abortSignal(TurnHandle handle) {
        return handle.onInterrupt()
                .then(Mono.error(new TurnInterruptedException(handle.turnId())));
    }
}
