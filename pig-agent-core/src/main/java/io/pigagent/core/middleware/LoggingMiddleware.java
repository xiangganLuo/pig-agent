package io.pigagent.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * Traces the agent reasoning/acting lifecycle at DEBUG — the AgentScope 2.0 middleware replacement for
 * the deleted 1.x {@code LoggingHook} (av2 Phase 5a). Purely observational: it never modifies the
 * downstream input, and logging is gated on {@code isDebugEnabled()} so it is near-zero cost otherwise.
 */
public final class LoggingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(LoggingMiddleware.class);

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Pre-reasoning", name(agent));
            return next.apply(input).doOnComplete(() -> log.debug("[{}] Post-reasoning", name(agent)));
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Pre-acting", name(agent));
            return next.apply(input).doOnComplete(() -> log.debug("[{}] Post-acting", name(agent)));
        }
        return next.apply(input);
    }

    private static String name(Agent agent) {
        return agent != null ? agent.getName() : "unknown";
    }
}
