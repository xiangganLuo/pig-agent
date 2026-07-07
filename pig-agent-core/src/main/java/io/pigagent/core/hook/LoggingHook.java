package io.pigagent.core.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Logging hook that traces agent lifecycle events at DEBUG (timestamps come from the log pattern).
 */
public final class LoggingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(LoggingHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (log.isDebugEnabled()) {
            String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";
            if (event instanceof PreReasoningEvent) {
                log.debug("[{}] Pre-reasoning", agentName);
            } else if (event instanceof PostReasoningEvent) {
                log.debug("[{}] Post-reasoning", agentName);
            } else if (event instanceof PreActingEvent) {
                log.debug("[{}] Pre-acting", agentName);
            } else if (event instanceof PostActingEvent) {
                log.debug("[{}] Post-acting", agentName);
            } else {
                log.debug("[{}] Event: {}", agentName, event.getClass().getSimpleName());
            }
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 50;
    }
}
