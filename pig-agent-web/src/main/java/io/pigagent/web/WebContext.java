package io.pigagent.web;

import io.pigagent.core.agent.kernel.AgentKernel;

import java.util.Objects;

/**
 * Immutable holder of the collaborators shared with every Web handler — the Web console's analogue
 * of the CLI's {@code ReplContext}, but deliberately minimal. This first version is a pure
 * {@link AgentKernel} adapter, so the context carries only the façade: agent lifecycle / chat /
 * events all flow through it. Extra managers (model / session / mcp / …) are intentionally NOT
 * wired here — the Web is "just another adapter" over the kernel, nothing more.
 */
public record WebContext(AgentKernel agentKernel) {

    public WebContext {
        Objects.requireNonNull(agentKernel, "agentKernel");
    }

    /** Minimal context over the kernel façade (agents + events + chat). */
    public static WebContext ofKernel(AgentKernel kernel) {
        return new WebContext(kernel);
    }
}
