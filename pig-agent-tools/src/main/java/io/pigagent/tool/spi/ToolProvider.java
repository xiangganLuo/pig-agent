package io.pigagent.tool.spi;

/**
 * SPI for auto-discovering a builtin tool. Implementations are declared in
 * {@code META-INF/services/io.pigagent.tool.spi.ToolProvider} and loaded via
 * {@link java.util.ServiceLoader} by {@link ToolRegistrar} — so adding a new tool means adding its
 * class + a tiny provider + one service line, with no edit to the assembly wiring
 * ({@code AgentBootstrap}). Each provider yields one tool instance (a class with {@code @Tool}
 * methods); return {@code null} to opt out (e.g. a tool disabled by its environment).
 */
public interface ToolProvider {

    /**
     * Create the tool instance to register, pulling any runtime dependencies from {@code context}.
     *
     * @return the tool instance, or {@code null} to register nothing
     */
    Object create(ToolContext context);
}
