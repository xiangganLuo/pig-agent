package io.pigagent.core.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import reactor.core.publisher.Mono;

/**
 * Hook that logs tool calls with their names and results.
 */
public final class ToolCallLoggingHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse() != null ? pre.getToolUse().getName() : "unknown";
            System.err.printf("[Tool] Calling: %s%n", toolName);
        } else if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse() != null ? post.getToolUse().getName() : "unknown";
            System.err.printf("[Tool] Completed: %s%n", toolName);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 60;
    }
}
