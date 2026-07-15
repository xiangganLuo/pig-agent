package io.pigagent.plugin.collection;

import io.pigagent.plugin.Plugin;
import io.pigagent.plugin.PluginContext;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/**
 * Common skeleton for every plugin in this collection, applying two GoF patterns so the concrete
 * plugins stay declarative rather than procedural:
 *
 * <ul>
 *   <li><b>Template Method</b> — {@link #register(PluginContext)} is {@code final}: it fixes the
 *       invariant assembly steps (read the shared {@link ToolContext}, build the tool set, contribute
 *       them all through {@link PluginContext#addTools}). A subclass cannot alter the wiring.</li>
 *   <li><b>Factory Method</b> — the single varying step is delegated to
 *       {@link #createTools(ToolContext)}; each concrete plugin only answers "which tools do I make",
 *       never "how are they wired".</li>
 * </ul>
 *
 * <p>The stable {@link #id()} (passed in via the constructor) is used by {@code PluginRegistry} for
 * logging and cross-source de-duplication. Tools returned by {@link #createTools} are plain
 * {@code @Tool} POJOs composed in — this collection deliberately has no tool inheritance tree
 * (composition over inheritance).
 */
public abstract class AbstractToolPlugin implements Plugin {

    private final String id;

    /**
     * @param id stable plugin id (e.g. {@code "collection:time"}); must not be {@code null}/blank —
     *           a blank id would break {@code PluginRegistry}'s id-based de-duplication.
     */
    protected AbstractToolPlugin(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("plugin id must not be null/blank");
        }
        this.id = id;
    }

    @Override
    public final String id() {
        return id;
    }

    /**
     * Template method: fixed assembly skeleton, not overridable. Pulls the shared runtime deps and
     * contributes every tool the concrete plugin's factory produces.
     */
    @Override
    public final void register(PluginContext ctx) {
        List<Object> tools = createTools(ctx.toolContext());
        ctx.addTools(tools);
    }

    /**
     * Factory method: build this plugin's tool instances (classes with {@code @Tool} methods). Called
     * once per {@code register}. Pull any runtime dependency from {@code context}; return an empty
     * list to contribute nothing. Never {@code null}.
     */
    protected abstract List<Object> createTools(ToolContext context);
}
