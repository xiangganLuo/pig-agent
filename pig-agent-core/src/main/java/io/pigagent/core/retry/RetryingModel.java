package io.pigagent.core.retry;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * A {@link Model} decorator that transparently retries the underlying model's streaming call on
 * transient failures (see {@link RetryPolicy}).
 *
 * <p><b>Why decorate the Model, not the agent stream:</b> {@code ReActAgent} is single-flight —
 * re-subscribing to {@code reactAgent.stream()} re-enters a still-running agent and throws
 * "Agent is still running". By applying retry here, a retry re-invokes only {@code
 * delegate.stream(...)} (a fresh HTTP call) <em>inside a single agent invocation</em>; the agent
 * calls the model once from its perspective and never sees the retry, so its running state is
 * never re-entered.
 *
 * <p>{@code Flux.defer} ensures the delegate's {@code stream} is invoked afresh on each retry
 * subscription, regardless of whether the delegate returns a cold or eager publisher.
 */
public final class RetryingModel implements Model {

    private final Model delegate;
    private final RetryPolicy retryPolicy;

    public RetryingModel(Model delegate, RetryPolicy retryPolicy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return retryPolicy.apply(Flux.defer(() -> delegate.stream(messages, tools, options)));
    }
}
