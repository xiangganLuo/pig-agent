package io.pigagent.cli;

import io.pigagent.channel.Channel;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.cli.repl.AgentRepl;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terminal (REPL) entry point. Builds the shared runtime via {@link AgentBootstrap}, starts the
 * channel bridges, then hands control to {@link AgentRepl} for the interactive (picocli + JLine)
 * loop. It deliberately does NOT start the Web console — that is an independent process launched
 * via {@link WebLauncher} over the same workspace. Colored output is produced via {@link Ansi}.
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

        // Shared runtime (agent, kernel, managers). The CLI is a REPL frontend on top of it — it does
        // NOT start the Web console; run WebLauncher for that (independent process, same workspace).
        AgentBootstrap.Services s = AgentBootstrap.build(true);

        List<ChannelAgentBridge> bridges = startChannels(s.channelAgentHolder, s.agentKernel, s.config.getChannels());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("CLI shutting down...");
            for (ChannelAgentBridge bridge : bridges) {
                bridge.stop();
            }
            s.shutdownCommon();
        }));

        new AgentRepl(s.agentHolder, s.agentKernel, s.workspace.getReportsDir(),
                s.configManager, s.registry, s.modelManager, s.compressionService,
                s.mcpManager, bridges, s.sessionManager, s.workspace.getRootPath(), s.readerRef).run();
    }

    private static List<ChannelAgentBridge> startChannels(AgentHolder agentHolder, AgentKernel agentKernel,
                                                          Map<String, PigAgentConfig.ChannelConfig> channelConfigs) {
        List<ChannelAgentBridge> bridges = new ArrayList<>();
        for (var entry : channelConfigs.entrySet()) {
            String id = entry.getKey();
            PigAgentConfig.ChannelConfig cfg = entry.getValue();
            if (!cfg.isEnabled()) continue;

            Channel channel = switch (id) {
                case "telegram" -> new TelegramChannel(cfg.getToken());
                case "discord" -> new DiscordChannel(cfg.getToken());
                default -> {
                    log.warn("Unknown channel type: {}, skipping", id);
                    yield null;
                }
            };
            if (channel == null) continue;

            ChannelAgentBridge bridge = new ChannelAgentBridge(agentHolder, channel, agentKernel);
            bridge.start();
            bridges.add(bridge);
            log.info("Channel started: {}", channel.displayName());
        }
        return bridges;
    }
}
