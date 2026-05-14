package io.pigagent.core.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Logging hook that prints agent lifecycle events to stderr.
 */
public final class LoggingHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        String timestamp = Instant.now().toString().substring(11, 19);
        String agentName = event.getAgent() != null ? event.getAgent().getName() : "unknown";

        if (event instanceof PreReasoningEvent) {
            System.err.printf("[%s] [%s] Pre-reasoning%n", timestamp, agentName);
        } else if (event instanceof PostReasoningEvent) {
            System.err.printf("[%s] [%s] Post-reasoning%n", timestamp, agentName);
        } else if (event instanceof PreActingEvent) {
            System.err.printf("[%s] [%s] Pre-acting%n", timestamp, agentName);
        } else if (event instanceof PostActingEvent) {
            System.err.printf("[%s] [%s] Post-acting%n", timestamp, agentName);
        } else {
            System.err.printf("[%s] [%s] Event: %s%n", timestamp, agentName, event.getClass().getSimpleName());
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 50;
    }
}
