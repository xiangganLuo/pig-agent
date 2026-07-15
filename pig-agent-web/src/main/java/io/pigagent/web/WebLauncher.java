package io.pigagent.web;

import io.pigagent.core.agent.kernel.AgentKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Config-gated starter for the embedded Web console. The entry module ({@code pig-agent-cli}) calls
 * {@link #startIfEnabled} after building the shared runtime, passing the SAME {@link AgentKernel}
 * the REPL drives — so the Web console is another adapter over the one kernel, in the same process.
 *
 * <p>It takes primitives (enabled / host / port) rather than a config object, so this module stays
 * dependency-light (kernel façade only, no {@code pig-agent-config}). Default disabled: with
 * {@code enabled=false} it starts nothing and returns {@link Optional#empty()} (backward compatible).
 */
public final class WebLauncher {

    private static final Logger log = LoggerFactory.getLogger(WebLauncher.class);

    private WebLauncher() {
    }

    /**
     * Start the console when {@code enabled}. Returns a stop-action ({@code console::stop}) as an
     * opaque {@link Runnable} — deliberately NOT the {@link WebConsole} type — so the caller (the CLI)
     * can wire it into its shutdown hook without its bytecode referencing any {@code pig-agent-web}
     * class in the exit path (avoids a {@link NoClassDefFoundError} on the web console class when the
     * web jar is absent from the runtime classpath). Empty when disabled. On a bind failure the error
     * is logged and empty is returned — a Web bind problem MUST NOT crash the CLI.
     */
    public static Optional<Runnable> startIfEnabled(AgentKernel kernel, boolean enabled,
                                                    String host, int port) {
        Objects.requireNonNull(kernel, "kernel");
        if (!enabled) {
            return Optional.empty();
        }
        WebConsole console = new WebConsole(WebContext.ofKernel(kernel), host, port);
        try {
            console.start();
        } catch (Exception e) {
            log.warn("Web console failed to start on {}:{} — {}", host, port, e.getMessage());
            return Optional.empty();
        }
        log.info("Web console: http://{}:{}  (loopback-only, single-user)", host, console.boundPort());
        return Optional.of(console::stop);
    }
}
