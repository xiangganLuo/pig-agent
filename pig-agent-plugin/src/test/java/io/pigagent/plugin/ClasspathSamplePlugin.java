package io.pigagent.plugin;

import io.agentscope.core.tool.Tool;

/**
 * A test plugin declared in the test-scoped {@code META-INF/services/io.pigagent.plugin.Plugin} file,
 * so {@link ServiceLoaderPluginSource} discovers it purely by "declaring a service line" — mirroring
 * how a real classpath plugin ships. Contributes one tool named {@code classpathPluginTool}.
 */
public final class ClasspathSamplePlugin implements Plugin {

    @Override
    public void register(PluginContext ctx) {
        ctx.addTool(new ClasspathTool());
    }

    /** Public so AgentScope's reflection can register the {@code @Tool} method. */
    public static final class ClasspathTool {
        @Tool(name = "classpathPluginTool", description = "a tool contributed by a classpath plugin")
        public String run() {
            return "ok";
        }
    }
}
