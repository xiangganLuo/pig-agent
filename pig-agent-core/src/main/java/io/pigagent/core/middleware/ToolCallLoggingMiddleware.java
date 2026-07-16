package io.pigagent.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Logs tool calls with their names at DEBUG — the AgentScope 2.0 middleware replacement for the deleted
 * 1.x {@code ToolCallLoggingHook} (av2 Phase 5a). Purely observational (never modifies the acting
 * input); logging is gated on {@code isDebugEnabled()}.
 */
public final class ToolCallLoggingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLoggingMiddleware.class);

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (log.isDebugEnabled()) {
            log.debug("Calling tool(s): {}", names(input.toolCalls()));
            return next.apply(input).doOnComplete(() -> log.debug("Completed tool(s): {}", names(input.toolCalls())));
        }
        return next.apply(input);
    }

    private static String names(List<ToolUseBlock> calls) {
        if (calls == null || calls.isEmpty()) {
            return "unknown";
        }
        return calls.stream()
                .map(tu -> tu == null || tu.getName() == null ? "unknown" : tu.getName())
                .collect(Collectors.joining(", "));
    }
}
