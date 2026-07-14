package io.pigagent.cli;

import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.ChannelFactory;
import io.pigagent.cli.repl.AgentRepl;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.web.WebConsole;
import io.pigagent.web.WebLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Terminal (REPL) entry point. Builds the shared runtime via {@link AgentBootstrap}, starts the
 * channel bridges, optionally starts the embedded Web console ({@code web.enabled}), then hands
 * control to {@link AgentRepl} for the interactive (picocli + JLine) loop. The Web console is
 * another {@link AgentKernel} adapter sharing the same kernel. Colored output is via {@link Ansi}.
 */
public final class PigAgentCli {

    private static final Logger log = LoggerFactory.getLogger(PigAgentCli.class);

    private static final String BANNER = """
                                                                  __     \s
                    __                                            /\\ \\__  \s
             _____ /\\_\\     __          __       __      __    ___\\ \\ ,_\\ \s
            /\\ '__`\\/\\ \\  /'_ `\\      /'__`\\   /'_ `\\  /'__`\\/' _ `\\ \\ \\/ \s
            \\ \\ \\L\\ \\ \\ \\/\\ \\L\\ \\    /\\ \\L\\.\\_/\\ \\L\\ \\/\\  __//\\ \\/\\ \\ \\ \\_\s
             \\ \\ ,__/\\ \\_\\ \\____ \\   \\ \\__/.\\_\\ \\____ \\ \\____\\ \\_\\ \\_\\ \\__\\
              \\ \\ \\/  \\/_/\\/___L\\ \\   \\/__/\\/_/\\/___L\\ \\/____/\\/_/\\/_/\\/__/
               \\ \\_\\        /\\____/              /\\____/                  \s
                \\/_/        \\_/__/               \\_/__/                   \s
            """;

    public static void main(String[] args) throws Exception {
        System.out.println(Ansi.heading(BANNER));

        // Shared runtime (agent, kernel, managers). The CLI is the REPL frontend on top of it.
        AgentBootstrap.Services s = AgentBootstrap.build(true);

        List<ChannelAgentBridge> bridges = startChannels(s.channelAgentHolder, s.agentKernel, s.config.getChannels());

        // Optional embedded Web console — another AgentKernel adapter in the same process, sharing
        // the one kernel with the REPL. Default disabled (web.enabled=false); loopback-only.
        PigAgentConfig.WebConfig webCfg = s.config.getWeb();
        Optional<WebConsole> webConsole = WebLauncher.startIfEnabled(
                s.agentKernel, webCfg.isEnabled(), webCfg.getHost(), webCfg.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("CLI shutting down...");
            webConsole.ifPresent(WebConsole::stop);
            for (ChannelAgentBridge bridge : bridges) {
                bridge.stop();
            }
            s.shutdownCommon();
        }));

        new AgentRepl(s.agentHolder, s.agentKernel, s.workspace.getReportsDir(),
                s.configManager, s.registry, s.modelManager, s.compressionService,
                s.mcpManager, bridges, s.sessionManager, s.workspace.getRootPath(), s.readerRef,
                s.availabilityReport).run();
    }

    private static List<ChannelAgentBridge> startChannels(AgentHolder agentHolder, AgentKernel agentKernel,
                                                          Map<String, PigAgentConfig.ChannelConfig> channelConfigs) {
        List<ChannelAgentBridge> bridges = new ArrayList<>();
        ChannelFactory factory = new ChannelFactory();
        // Construction + enable gating live in ChannelFactory (unit-tested); adding a new adapter
        // needs no edit here. Each enabled, known channel is bridged to the live agent and started.
        for (var entry : channelConfigs.entrySet()) {
            factory.create(entry.getKey(), entry.getValue()).ifPresent(channel -> {
                ChannelAgentBridge bridge = new ChannelAgentBridge(agentHolder, channel, agentKernel);
                bridge.start();
                bridges.add(bridge);
                log.info("Channel started: {}", channel.displayName());
            });
        }
        return bridges;
    }
}
