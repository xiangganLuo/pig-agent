package io.pigagent.cli;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.hook.LoggingHook;
import io.pigagent.core.hook.ToolCallLoggingHook;
import io.pigagent.core.provider.AgentOnboardingProvider;
import io.pigagent.mcp.McpManager;
import io.pigagent.onboarding.OnboardingWizard;
import io.pigagent.provider.registry.ProviderRegistry;
import io.pigagent.provider.anthropic.AnthropicProvider;
import io.pigagent.provider.openai.OpenAiProvider;
import io.pigagent.provider.ollama.OllamaProvider;
import io.pigagent.provider.gemini.GeminiProvider;
import io.pigagent.provider.dashscope.DashScopeProvider;
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
import java.util.List;

public final class PigAgentCli {

    public static void main(String[] args) throws Exception {
        System.out.println("  ___  _          _              _     _           ");
        System.out.println(" |  _(_) __ _  __| | __ _  ___  / \\   (_)______ _  ");
        System.out.println(" | |_| |/ _` |/ _` |/ _` |/ _ \\/ _ \\  | |_  / _` | ");
        System.out.println(" |  _| | (_| | (_| | (_| |  __/ ___ \\ | |/ / (_| | ");
        System.out.println(" |_| |_|\\__,_|\\__,_|\\__, |\\___/_/   \\_\\/___|\\__,_| ");
        System.out.println("                     |___/                          \n");

        WorkspaceManager workspace = WorkspaceManager.defaultWorkspace();
        workspace.initialize();
        System.out.println("Workspace: " + workspace.getRootPath().toAbsolutePath());

        Path configPath = workspace.getRootPath().resolve("application.yaml");
        ConfigurationManager configManager = new ConfigurationManager(configPath);
        PigAgentConfig config = configManager.getConfig();

        ProviderRegistry registry = new ProviderRegistry();
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
        PigAgent agent = PigAgent.builder()
                .name(config.getAgent().getName())
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .hooks(List.of(new LoggingHook(), new ToolCallLoggingHook()))
                .build();

        startRepl(agent);
    }

    private static void startRepl(PigAgent agent) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        System.out.println("Agent ready. Type your message or /help for commands.\n");

        while (true) {
            String input = reader.readLine("you> ");
            if (input == null || input.isBlank()) continue;

            if (input.startsWith("/")) {
                if (!handleCommand(input, agent)) break;
                continue;
            }

            Msg userMsg = Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text(input).build()).build();

            System.out.print("agent> ");
            try {
                Msg response = agent.call(userMsg);
                System.out.println(response.getTextContent());
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }

    private static boolean handleCommand(String input, PigAgent agent) {
        String cmd = input.split("\\s+")[0].toLowerCase();
        switch (cmd) {
            case "/help" -> {
                System.out.println("Commands:");
                System.out.println("  /help        Show this help");
                System.out.println("  /tasks       List tasks");
                System.out.println("  /skills      List skills");
                System.out.println("  /quit        Exit");
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
            default -> System.out.println("Unknown command: " + cmd + ". Type /help.");
        }
        return true;
    }
}
