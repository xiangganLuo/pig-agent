package io.pigagent.cli;

import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.ChannelFactory;
import io.pigagent.channel.ChannelRegistry;
import io.pigagent.channel.gateway.GatewayChannelKernel;
import io.pigagent.channel.gateway.GatewayOutboundChannel;
import io.pigagent.channel.gateway.NativeChannelFactory;
import io.pigagent.cli.repl.AgentRepl;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
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

        ChannelStartup channels = startChannels(s.channelAgentHolder, s.agentKernel, s.config, s.outreachRegistry);
        List<ChannelAgentBridge> bridges = channels.bridges();

        // Register started channels into the outreach registry so proactive outreach can find outbound
        // channels (D9 — resolved lazily at notify time; channels start after AgentBootstrap.build).
        for (ChannelAgentBridge bridge : bridges) {
            s.outreachRegistry.register(bridge.getChannel());
        }

        // Optional embedded Web console — another AgentKernel adapter in the same process, sharing
        // the one kernel with the REPL. Default disabled (web.enabled=false); loopback-only.
        PigAgentConfig.WebConfig webCfg = s.config.getWeb();
        // Opaque Runnable stop-handle (not the WebConsole type) so this class's shutdown path never
        // references a pig-agent-web class — see WebLauncher#startIfEnabled.
        Optional<Runnable> webStop = WebLauncher.startIfEnabled(
                s.agentKernel, webCfg.isEnabled(), webCfg.getHost(), webCfg.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("CLI shutting down...");
            // Each step guarded so one failure never aborts the rest of cleanup.
            runQuietly(() -> webStop.ifPresent(Runnable::run));
            for (ChannelAgentBridge bridge : bridges) {
                runQuietly(bridge::stop);
            }
            if (channels.kernel() != null) {
                runQuietly(channels.kernel()::stop);
            }
            runQuietly(s::shutdownCommon);
        }));

        new AgentRepl(s.agentHolder, s.agentKernel, s.workspace.getReportsDir(),
                s.configManager, s.registry, s.modelManager, s.compressionService,
                s.mcpManager, bridges, s.sessionManager, s.workspace.getRootPath(), s.readerRef,
                s.availabilityReport, s.notificationService, s.skillGate).run();
    }

    /** Run a shutdown step, swallowing+logging any error so one failure never aborts the rest. */
    private static void runQuietly(Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            log.warn("Shutdown step failed: {}", t.toString());
        }
    }

    /** The started channel bridges plus the (optional) native gateway kernel, for shutdown. */
    record ChannelStartup(List<ChannelAgentBridge> bridges, GatewayChannelKernel kernel) {
    }

    /**
     * Start the configured channels. Two paths, gated by {@code channel-gateway.enabled}:
     * <ul>
     *   <li><b>Default (disabled):</b> each enabled, known channel is a pig custom adapter bridged
     *       directly to the channel agent — <em>exactly the prior behavior</em> (backward compatible).</li>
     *   <li><b>Native gateway (enabled):</b> a {@link GatewayChannelKernel} over the channel agent's
     *       {@code HarnessAgent} becomes the routing engine (native session/concurrency/routing).
     *       Channels flagged {@code native:true} whose {@code agentscope-extensions-channel-*} artifact
     *       is on the classpath are attached as native adapters (and registered as outreach outbound
     *       targets); every other channel stays a pig custom adapter but is <em>routed through the
     *       native gateway</em>. When a native artifact is absent it degrades gracefully to the custom
     *       adapter (this is always the case in the offline build — see {@link NativeChannelFactory}).</li>
     * </ul>
     */
    private static ChannelStartup startChannels(AgentHolder agentHolder, AgentKernel agentKernel,
                                                PigAgentConfig config, ChannelRegistry outreachRegistry) {
        Map<String, PigAgentConfig.ChannelConfig> channelConfigs = config.getChannels();
        ChannelFactory factory = new ChannelFactory();

        if (!config.getChannelGateway().isEnabled()) {
            // Default path (unchanged): custom adapters bridged directly to the channel agent.
            List<ChannelAgentBridge> bridges = new ArrayList<>();
            for (var entry : channelConfigs.entrySet()) {
                factory.create(entry.getKey(), entry.getValue()).ifPresent(channel -> {
                    ChannelAgentBridge bridge = new ChannelAgentBridge(agentHolder, channel, agentKernel);
                    bridge.start();
                    bridges.add(bridge);
                    log.info("Channel started: {}", channel.displayName());
                });
            }
            return new ChannelStartup(bridges, null);
        }

        // Native gateway path (opt-in): adopt the native Gateway/ChatUiChannel as the routing kernel.
        String mainAgentId = config.getChannelGateway().getMainAgentId();
        NativeChannelFactory nativeFactory = new NativeChannelFactory(mainAgentId);
        GatewayChannelKernel.Builder kernelBuilder =
                GatewayChannelKernel.builder(agentHolder.get().getHarnessAgent());

        // Resolve native adapters (native:true + artifact present); the rest are custom adapters.
        List<io.agentscope.harness.agent.gateway.channel.Channel> natives = new ArrayList<>();
        List<Map.Entry<String, PigAgentConfig.ChannelConfig>> customEntries = new ArrayList<>();
        for (var entry : channelConfigs.entrySet()) {
            Optional<io.agentscope.harness.agent.gateway.channel.Channel> nativeCh =
                    nativeFactory.create(entry.getKey(), entry.getValue());
            if (nativeCh.isPresent()) {
                natives.add(nativeCh.get());
                kernelBuilder.nativeChannel(nativeCh.get());
            } else if (entry.getValue() != null && entry.getValue().isEnabled()) {
                customEntries.add(entry);
            }
        }

        GatewayChannelKernel kernel = kernelBuilder.build();
        kernel.start(); // inits + starts native adapters on the gateway
        log.info("Native channel gateway enabled (main agent '{}', {} native adapter(s))",
                mainAgentId, natives.size());

        // Native adapters are outbound targets for proactive outreach (part 4 — the send-seam retarget).
        for (io.agentscope.harness.agent.gateway.channel.Channel nativeCh : natives) {
            outreachRegistry.register(new GatewayOutboundChannel(nativeCh));
        }

        // Custom adapters keep their pig inbound transport but route turns through the native gateway.
        List<ChannelAgentBridge> bridges = new ArrayList<>();
        for (var entry : customEntries) {
            factory.create(entry.getKey(), entry.getValue()).ifPresent(channel -> {
                ChannelAgentBridge bridge = new ChannelAgentBridge(agentHolder, channel, agentKernel, kernel);
                bridge.start();
                bridges.add(bridge);
                log.info("Channel started (native gateway routing): {}", channel.displayName());
            });
        }
        return new ChannelStartup(bridges, kernel);
    }
}
