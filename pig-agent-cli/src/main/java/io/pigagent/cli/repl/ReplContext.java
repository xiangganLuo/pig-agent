package io.pigagent.cli.repl;

import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.provider.registry.ProviderRegistry;
import io.pigagent.session.SessionManager;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Immutable bundle of collaborators shared with every REPL command.
 *
 * <p>The {@code running} flag lets a command (e.g. {@code /quit}) request the read loop in
 * {@link AgentRepl} to terminate. {@code readerRef} holds the JLine {@link LineReader} once
 * it has been built (it is set by {@link AgentRepl} after construction, which resolves the
 * context → command tree → reader build-order cycle); commands use it for interactive
 * confirmation prompts such as session deletion.
 */
public record ReplContext(
        PigAgent agent,
        ConfigurationManager configManager,
        ProviderRegistry registry,
        List<ChannelAgentBridge> bridges,
        SessionManager sessionManager,
        Terminal terminal,
        AtomicBoolean running,
        AtomicReference<LineReader> readerRef) {
}
