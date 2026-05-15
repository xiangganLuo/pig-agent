package io.pigagent.cli;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.hook.LoggingHook;
import io.pigagent.core.hook.ToolCallLoggingHook;
import io.pigagent.core.memory.FileSystemLongTermMemory;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.channel.Channel;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.ChannelRegistry;
import io.pigagent.channel.chat.ChatChannel;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.mcp.McpManager;
import io.pigagent.onboarding.OnboardingWizard;
import io.pigagent.provider.registry.ProviderRegistry;
import io.pigagent.provider.anthropic.AnthropicProvider;
import io.pigagent.provider.openai.OpenAiProvider;
import io.pigagent.provider.ollama.OllamaProvider;
import io.pigagent.provider.gemini.GeminiProvider;
import io.pigagent.provider.dashscope.DashScopeProvider;
import io.pigagent.provider.mimo.MimoProvider;
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
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PigAgentCli {

    public static void main(String[] args) throws Exception {
        System.out.println("""
                                                                      __     \s
                        __                                            /\\ \\__  \s
                 _____ /\\_\\     __          __       __      __    ___\\ \\ ,_\\ \s
                /\\ '__`\\/\\ \\  /'_ `\\      /'__`\\   /'_ `\\  /'__`\\/' _ `\\ \\ \\/ \s
                \\ \\ \\L\\ \\ \\ \\/\\ \\L\\ \\    /\\ \\L\\.\\_/\\ \\L\\ \\/\\  __//\\ \\/\\ \\ \\ \\_\s
                 \\ \\ ,__/\\ \\_\\ \\____ \\   \\ \\__/.\\_\\ \\____ \\ \\____\\ \\_\\ \\_\\ \\__\\
                  \\ \\ \\/  \\/_/\\/___L\\ \\   \\/__/\\/_/\\/___L\\ \\/____/\\/_/\\/_/\\/__/
                   \\ \\_\\        /\\____/              /\\____/                  \s
                    \\/_/        \\_/__/               \\_/__/                   \s
                
                """);

        WorkspaceManager workspace = WorkspaceManager.defaultWorkspace();
        workspace.initialize();
        System.out.println("Workspace: " + workspace.getRootPath().toAbsolutePath());

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
        FileSystemLongTermMemory longTermMemory = new FileSystemLongTermMemory(
                workspace.getContextDir().resolve("memory.md"));

        PigAgent agent = PigAgent.builder()
                .name(config.getAgent().getName())
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .hooks(List.of(new LoggingHook(), new ToolCallLoggingHook()))
                .longTermMemory(longTermMemory)
                .build();

        List<ChannelAgentBridge> bridges = startChannels(agent, config.getChannels());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("\n[CLI] Shutting down...");
            for (ChannelAgentBridge bridge : bridges) {
                bridge.stop();
            }
            taskScheduler.shutdown();
            mcpManager.closeAll();
        }));

        startRepl(agent, configManager, registry, bridges);
    }

    private static List<ChannelAgentBridge> startChannels(PigAgent agent, Map<String, PigAgentConfig.ChannelConfig> channelConfigs) {
        List<ChannelAgentBridge> bridges = new ArrayList<>();
        for (var entry : channelConfigs.entrySet()) {
            String id = entry.getKey();
            PigAgentConfig.ChannelConfig cfg = entry.getValue();
            if (!cfg.isEnabled()) continue;

            Channel channel = switch (id) {
                case "telegram" -> new TelegramChannel(cfg.getToken());
                case "discord" -> new DiscordChannel(cfg.getToken());
                default -> {
                    System.err.println("[Channel] Unknown channel type: " + id + ", skipping");
                    yield null;
                }
            };
            if (channel == null) continue;

            ChannelAgentBridge bridge = new ChannelAgentBridge(agent, channel);
            bridge.start();
            bridges.add(bridge);
            System.out.println("Channel started: " + channel.displayName());
        }
        return bridges;
    }

    private static void startRepl(PigAgent agent, ConfigurationManager configManager,
                                  ProviderRegistry registry, List<ChannelAgentBridge> bridges) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        System.out.println("Agent ready. Type your message or /help for commands.\n");

        while (true) {
            String input = reader.readLine("you> ");
            if (input == null || input.isBlank()) continue;

            if (input.startsWith("/")) {
                if (!handleCommand(input, agent, configManager, registry, bridges)) break;
                continue;
            }

            Msg userMsg = Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text(input).build()).build();

            System.out.print("agent> ");
            try {
                StringBuilder finalResponse = new StringBuilder();
                agent.stream(userMsg).doOnNext(event -> {
                    if (event.getType() == EventType.REASONING) {
                        System.err.println("\n  [thinking] " + event.getMessage().getTextContent());
                    } else if (event.getType() == EventType.TOOL_RESULT) {
                        System.err.println("  [tool] " + event.getMessage().getTextContent());
                    } else if (event.getType() == EventType.AGENT_RESULT) {
                        finalResponse.append(event.getMessage().getTextContent());
                    }
                }).doOnComplete(() -> {
                    if (!finalResponse.isEmpty()) {
                        System.out.println(finalResponse);
                    }
                }).doOnError(e -> {
                    System.err.println("\nError: " + e.getMessage());
                    e.printStackTrace(System.err);
                }).blockLast();
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace(System.err);
            }
            System.out.println();
        }
    }

    private static boolean handleCommand(String input, PigAgent agent, ConfigurationManager configManager,
                                         ProviderRegistry registry, List<ChannelAgentBridge> bridges) {
        String cmd = input.split("\\s+")[0].toLowerCase();
        switch (cmd) {
            case "/help" -> {
                System.out.println("Commands:");
                System.out.println("  /help              Show this help");
                System.out.println("  /tasks             List all tasks");
                System.out.println("  /skills            List available skills");
                System.out.println("  /config            Show current configuration");
                System.out.println("  /providers         List all LLM providers and availability");
                System.out.println("  /model             Show current model details");
                System.out.println("  /switch <provider> Switch to a different provider");
                System.out.println("  /channels          Show connected channels and status");
                System.out.println("  /status            Show agent status summary");
                System.out.println("  /clear             Clear the screen");
                System.out.println("  /quit              Exit");
            }
            case "/quit", "/exit" -> {
                System.out.println("Goodbye!");
                return false;
            }
            case "/tasks" -> {
                Msg msg = Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("List all tasks").build()).build();
                System.out.println(agent.call(msg).getTextContent());
            }
            case "/skills" -> {
                Msg msg = Msg.builder().name("user").role(MsgRole.USER)
                        .content(TextBlock.builder().text("List available skills").build()).build();
                System.out.println(agent.call(msg).getTextContent());
            }
            case "/config" -> {
                var cfg = configManager.getConfig();
                System.out.println("Provider:  " + cfg.getModel().getProvider());
                System.out.println("Model:     " + cfg.getModel().getModelName());
                System.out.println("Agent:     " + cfg.getAgent().getName());
                System.out.println("Max Iters: " + cfg.getAgent().getMaxIters());
                System.out.println("MCP:       " + cfg.getMcp().getServers().keySet());
                System.out.println("Channels:  " + cfg.getChannels().keySet());
            }
            case "/providers" -> {
                var cfg = configManager.getConfig();
                String current = cfg.getModel().getProvider();
                System.out.println("Registered providers:");
                for (var p : registry.getAllProviders()) {
                    String marker = p.providerId().equals(current) ? " (active)" : "";
                    String available = p.isAvailable() ? "ready" : "no API key";
                    System.out.printf("  %-12s  %-20s  %s  %s%n",
                            p.providerId(), p.displayName(), available, marker);
                }
            }
            case "/model" -> {
                var cfg = configManager.getConfig();
                String providerId = cfg.getModel().getProvider();
                var providerOpt = registry.findById(providerId);
                System.out.println("=== Current Model ===");
                System.out.println("Provider:    " + providerId);
                System.out.println("Model:       " + cfg.getModel().getModelName());
                providerOpt.ifPresent(p -> {
                    System.out.println("Display:     " + p.displayName());
                    System.out.println("Description: " + p.description());
                    System.out.println("Credentials: " + p.requiredCredentialKeys());
                    System.out.println("Available:   " + p.isAvailable());
                });
            }
            case "/switch" -> {
                String[] parts = input.split("\\s+");
                if (parts.length < 2) {
                    System.out.println("Usage: /switch <provider>");
                    System.out.println("Available: " + registry.getAllProviders().stream()
                            .map(io.pigagent.core.provider.AgentOnboardingProvider::providerId)
                            .toList());
                    break;
                }
                String targetProvider = parts[1].toLowerCase();
                var providerOpt = registry.findById(targetProvider);
                if (providerOpt.isEmpty()) {
                    System.out.println("Unknown provider: " + targetProvider);
                    break;
                }
                var target = providerOpt.get();
                if (!target.isAvailable()) {
                    System.out.println("Provider '" + targetProvider + "' is not available. Check API key.");
                    break;
                }
                configManager.updateConfig(cfg -> {
                    cfg.getModel().setProvider(targetProvider);
                    cfg.getModel().setModelName(target.defaultModelName());
                });
                System.out.println("Switched to " + target.displayName() + " / " + target.defaultModelName());
                System.out.println("Note: restart required for model change to take effect.");
            }
            case "/channels" -> {
                if (bridges.isEmpty()) {
                    System.out.println("No channels connected.");
                } else {
                    System.out.println("Connected channels:");
                    for (var bridge : bridges) {
                        var ch = bridge.getChannel();
                        System.out.printf("  %-12s  %-16s  %s%n",
                                ch.channelId(), ch.displayName(),
                                ch.isRunning() ? "running" : "stopped");
                    }
                }
            }
            case "/status" -> {
                var cfg = configManager.getConfig();
                System.out.println("=== Agent Status ===");
                System.out.println("Agent:     " + agent.getAgentName());
                System.out.println("Provider:  " + cfg.getModel().getProvider());
                System.out.println("Model:     " + cfg.getModel().getModelName());
                System.out.println("Channels:  " + bridges.stream()
                        .filter(b -> b.getChannel().isRunning())
                        .count() + " running");
                System.out.println("MCP:       " + cfg.getMcp().getServers().size() + " configured");
            }
            case "/clear" -> {
                System.out.print("\033[2J\033[H");
                System.out.flush();
            }
            default -> System.out.println("Unknown command: " + cmd + ". Type /help.");
        }
        return true;
    }
}
