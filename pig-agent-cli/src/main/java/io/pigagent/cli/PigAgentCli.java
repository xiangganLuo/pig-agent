package io.pigagent.cli;

import io.agentscope.core.session.JsonSession;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.channel.Channel;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.cli.repl.AgentRepl;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.hook.LoggingHook;
import io.pigagent.core.hook.ToolCallLoggingHook;
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.mcp.McpManager;
import io.pigagent.onboarding.OnboardingWizard;
import io.pigagent.provider.anthropic.AnthropicProvider;
import io.pigagent.provider.dashscope.DashScopeProvider;
import io.pigagent.provider.gemini.GeminiProvider;
import io.pigagent.provider.mimo.MimoProvider;
import io.pigagent.provider.ollama.OllamaProvider;
import io.pigagent.provider.openai.OpenAiProvider;
import io.pigagent.provider.registry.ProviderRegistry;
import io.pigagent.session.FileSystemSessionRepository;
import io.pigagent.session.SessionManager;
import io.pigagent.session.SessionRepository;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskScheduler;
import io.pigagent.tool.checklist.CheckListTool;
import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.task.TaskTool;
import io.pigagent.tool.webfetch.SmartWebFetchTool;
import io.pigagent.tool.websearch.BraveWebSearchTool;
import io.pigagent.workspace.WorkspaceManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Terminal entry point. Bootstraps the workspace, configuration, providers, tools,
 * MCP connections, the agent and channels, then hands control to {@link AgentRepl}
 * for the interactive (picocli + JLine) loop. Colored output is produced via {@link Ansi}.
 */
public final class PigAgentCli {

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

        WorkspaceManager workspace = WorkspaceManager.defaultWorkspace();
        workspace.initialize();
        System.out.println(Ansi.dim("Workspace: ") + Ansi.info(workspace.getRootPath().toAbsolutePath().toString()));

        Path configPath = workspace.getRootPath().resolve("application.yaml");
        ConfigurationManager configManager = new ConfigurationManager(configPath);
        PigAgentConfig config = configManager.getConfig();

        ProviderRegistry registry = new ProviderRegistry();
        registry.register(new MimoProvider());
        registry.register(new AnthropicProvider());
        registry.register(new OpenAiProvider());
        registry.register(new OllamaProvider());
        registry.register(new GeminiProvider());
        registry.register(new DashScopeProvider());

        String initialProviderId = config.getModel().getProvider();
        var providerOpt = registry.findById(initialProviderId);
        if (providerOpt.isEmpty() || !providerOpt.get().isAvailable()) {
            new OnboardingWizard(registry, configManager, workspace).run();
            config = configManager.getConfig();
        }

        final String providerId = config.getModel().getProvider();
        AgentOnboardingProvider provider = registry.findById(providerId)
                .orElseThrow(() -> new IllegalStateException("Provider not found: " + providerId));
        var model = provider.createModelFromEnv();

        TaskManager taskManager = new TaskManager(new FileSystemTaskRepository(workspace.getTasksDir()));
        TaskScheduler taskScheduler = new TaskScheduler(taskManager);
        taskScheduler.scheduleAll();

        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new TaskTool(taskManager)).apply();
        toolkit.registration().tool(new ShellTools()).apply();
        toolkit.registration().tool(new FileSystemTools()).apply();
        toolkit.registration().tool(new SmartWebFetchTool()).apply();
        toolkit.registration().tool(new BraveWebSearchTool()).apply();
        toolkit.registration().tool(new CheckListTool()).apply();
        toolkit.registration().tool(new SkillsTool(workspace.getSkillsDir())).apply();

        McpManager mcpManager = new McpManager();
        mcpManager.connectAll(config.getMcp(), toolkit);

        String sysPrompt = workspace.readAgentMd() + "\n\n" + workspace.readInfoMd();
        // Two-tier memory: shared global memory + a switchable per-session temporary memory.
        FileSystemLongTermMemory globalMemory = new FileSystemLongTermMemory(
                workspace.getContextDir().resolve("memory.md"));
        CompositeLongTermMemory memory = new CompositeLongTermMemory(globalMemory, config.isMemoryEnabled());

        PigAgent agent = PigAgent.builder()
                .name(config.getAgent().getName())
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .hooks(List.of(new LoggingHook(), new ToolCallLoggingHook()))
                .longTermMemory(memory)
                .build();

        // Session management: AgentScope's JsonSession persists each session's conversation;
        // our repository tracks listing metadata. initialize() restores the last active session.
        JsonSession agentSession = new JsonSession(workspace.getSessionsDir());
        SessionRepository sessionRepository = new FileSystemSessionRepository(workspace.getSessionsDir());
        SessionManager sessionManager = new SessionManager(
                agent, agentSession, memory, sessionRepository, configManager, workspace.getSessionsDir());
        sessionManager.initialize();
        sessionManager.getCurrentSession().ifPresent(s ->
                System.out.println(Ansi.success("Session: ") + Ansi.info(s.name() + " [" + s.id() + "]")));

        List<ChannelAgentBridge> bridges = startChannels(agent, config.getChannels());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println(Ansi.warn("\n[CLI] Shutting down..."));
            for (ChannelAgentBridge bridge : bridges) {
                bridge.stop();
            }
            sessionManager.saveCurrent();
            agentSession.close();
            taskScheduler.shutdown();
            mcpManager.closeAll();
        }));

        new AgentRepl(agent, configManager, registry, bridges, sessionManager, workspace.getRootPath()).run();
    }

    private static List<ChannelAgentBridge> startChannels(PigAgent agent,
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
                    System.err.println(Ansi.warn("[Channel] Unknown channel type: " + id + ", skipping"));
                    yield null;
                }
            };
            if (channel == null) continue;

            ChannelAgentBridge bridge = new ChannelAgentBridge(agent, channel);
            bridge.start();
            bridges.add(bridge);
            System.out.println(Ansi.success("Channel started: ") + Ansi.info(channel.displayName()));
        }
        return bridges;
    }
}
