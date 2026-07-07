package io.pigagent.core.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that logs tool calls with their names and results (DEBUG).
 */
public final class ToolCallLoggingHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLoggingHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse() != null ? pre.getToolUse().getName() : "unknown";
            log.debug("Calling tool: {}", toolName);
        } else if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse() != null ? post.getToolUse().getName() : "unknown";
            log.debug("Completed tool: {}", toolName);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 60;
    }
}
