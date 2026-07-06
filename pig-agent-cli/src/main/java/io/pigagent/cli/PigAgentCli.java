package io.pigagent.cli;

import io.agentscope.core.session.JsonSession;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.channel.Channel;
import io.pigagent.channel.ChannelAgentBridge;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.cli.repl.AgentRepl;
import io.pigagent.config.ConfigurationManager;
import io.pigagent.config.PigAgentConfig;
import io.pigagent.core.agent.AgentFactory;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.AgentSpecRepository;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.kernel.AgentKernel;
import io.pigagent.core.agent.runner.AgentRunner;
import io.pigagent.core.agent.runner.FileReportWriter;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.core.hook.LoggingHook;
import io.pigagent.core.hook.ToolCallLoggingHook;
import io.pigagent.core.retry.RetryPolicy;
import io.pigagent.core.retry.TransientErrorClassifier;
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;
import io.pigagent.mcp.JsonMcpStore;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.onboarding.OnboardingWizard;
import io.pigagent.provider.anthropic.AnthropicProtocol;
import io.pigagent.provider.dashscope.DashScopeProtocol;
import io.pigagent.provider.gemini.GeminiProtocol;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.FileSystemSessionRepository;
import io.pigagent.session.SessionManager;
import io.pigagent.session.SessionRepository;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskSchedule;
import io.pigagent.task.TaskScheduler;
import io.pigagent.tool.checklist.CheckListTool;
import io.pigagent.tool.mcp.McpConfirmer;
import io.pigagent.tool.mcp.McpTool;
import io.pigagent.tool.permission.AllowlistWriter;
import io.pigagent.tool.permission.PermissionConfirmer;
import io.pigagent.tool.permission.PermissionDeniedTool;
import io.pigagent.tool.permission.ToolPermissionHook;
import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.task.TaskTool;
import io.pigagent.tool.webfetch.SmartWebFetchTool;
import io.pigagent.tool.websearch.BraveWebSearchTool;
import io.pigagent.workspace.WorkspaceManager;

import org.jline.reader.LineReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

        ProtocolRegistry registry = new ProtocolRegistry();
        registry.register(new OpenAiProtocol());
        registry.register(new AnthropicProtocol());
        registry.register(new GeminiProtocol());
        registry.register(new OllamaProtocol());
        registry.register(new DashScopeProtocol());

        // Model store + manager. No default model → force onboarding (cannot be skipped).
        JsonModelStore modelStore = new JsonModelStore(workspace.getModelsFile());
        ModelManager modelManager = new ModelManager(registry, modelStore);
        if (!modelManager.isConfigured()) {
            new OnboardingWizard(registry, modelManager).run();
        }
        StoredModel defaultModel = modelManager.getDefault()
                .orElseThrow(() -> new IllegalStateException("No model configured"));

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
        // Deny sentinel: the permission hook rewrites vetoed tool calls to this read-only tool.
        toolkit.registration().tool(new PermissionDeniedTool()).apply();

        // MCP: mcp.json is the source of truth; application.yaml servers are imported once.
        // Enabled servers are connected best-effort (a bad server never crashes startup).
        McpManager mcpManager = new McpManager();
        mcpManager.initialize(new JsonMcpStore(workspace.getMcpFile()), toolkit, config.getMcp());

        // The agent may self-manage MCP servers, gated by mcp.agent-management (D-SEC).
        // Human confirmation reads from the live REPL reader (shared via readerRef).
        AtomicReference<LineReader> readerRef = new AtomicReference<>();
        McpConfirmer confirmer = prompt -> {
            LineReader r = readerRef.get();
            if (r == null) {
                return false;
            }
            String answer = r.readLine(Ansi.warn(prompt + " (y/N) "));
            return answer != null && answer.strip().equalsIgnoreCase("y");
        };
        toolkit.registration().tool(new McpTool(mcpManager,
                () -> configManager.getConfig().getMcp().getAgentManagement(), confirmer)).apply();

        String sysPrompt = workspace.readAgentMd() + "\n\n" + workspace.readInfoMd();
        // Two-tier memory: shared global memory + a switchable per-session temporary memory.
        FileSystemLongTermMemory globalMemory = new FileSystemLongTermMemory(
                workspace.getContextDir().resolve("memory.md"));
        CompositeLongTermMemory memory = new CompositeLongTermMemory(globalMemory, config.isMemoryEnabled());

        // Tool permission gate (plan/ask/auto/bypass): a high-priority PreActingEvent hook that
        // vetoes tool calls per the current mode by rewriting them to the deny sentinel. ASK reads
        // y/a/N from the live REPL reader; 'a' persists to permissions.allowlist.
        PermissionConfirmer permissionConfirmer = prompt -> {
            LineReader r = readerRef.get();
            if (r == null) {
                return PermissionConfirmer.Outcome.DENY; // fail-closed when no interactive reader
            }
            String ans = r.readLine(Ansi.warn(prompt + " (y=once / a=always / N=deny) "));
            if (ans == null) {
                return PermissionConfirmer.Outcome.DENY;
            }
            String s = ans.strip().toLowerCase();
            if (s.equals("y")) {
                return PermissionConfirmer.Outcome.ALLOW_ONCE;
            }
            if (s.equals("a")) {
                return PermissionConfirmer.Outcome.ALLOW_ALWAYS;
            }
            return PermissionConfirmer.Outcome.DENY;
        };
        AllowlistWriter allowlistWriter = new AllowlistWriter() {
            @Override
            public void rememberTool(String toolName) {
                configManager.updateConfig(c -> {
                    List<String> l = c.getPermissions().getAllowlist().getTools();
                    if (!l.contains(toolName)) {
                        l.add(toolName);
                    }
                });
            }

            @Override
            public void rememberCommand(String commandKey) {
                configManager.updateConfig(c -> {
                    List<String> l = c.getPermissions().getAllowlist().getCommands();
                    if (!l.contains(commandKey)) {
                        l.add(commandKey);
                    }
                });
            }
        };
        ToolPermissionHook permissionHook = new ToolPermissionHook(
                () -> configManager.getConfig().getPermissions(), permissionConfirmer, allowlistWriter);

        // Model retry: auto-retry transient upstream failures (5xx/timeout/network) per model.retry.
        // Interactive retries print to the REPL terminal; channel retries log. The connectivity probe
        // (ModelManager.test) builds its agent without a policy, so it stays fast-fail.
        PigAgentConfig.RetryConfig rc = config.getModel().getRetry();
        TransientErrorClassifier retryClassifier = new TransientErrorClassifier();
        RetryPolicy interactiveRetry = new RetryPolicy(
                rc.isEnabled(), rc.getMaxRetries(),
                java.time.Duration.ofSeconds(rc.getPerAttemptTimeoutSeconds()),
                java.time.Duration.ofMillis(rc.getFirstBackoffMs()),
                java.time.Duration.ofMillis(rc.getMaxBackoffMs()),
                retryClassifier,
                (attempt, max, cause, backoff) -> {
                    String line = Ansi.warn(String.format("[retry %d/%d] %s, backing off %dms…",
                            attempt, max, retryCauseSummary(cause), backoff.toMillis()));
                    LineReader r = readerRef.get();
                    if (r != null) {
                        r.getTerminal().writer().println(line);
                        r.getTerminal().writer().flush();
                    } else {
                        System.err.println(line);
                    }
                });
        RetryPolicy channelRetry = new RetryPolicy(
                rc.isEnabled(), rc.getMaxRetries(),
                java.time.Duration.ofSeconds(rc.getPerAttemptTimeoutSeconds()),
                java.time.Duration.ofMillis(rc.getFirstBackoffMs()),
                java.time.Duration.ofMillis(rc.getMaxBackoffMs()),
                retryClassifier,
                (attempt, max, cause, backoff) -> System.err.println(String.format(
                        "[channel retry %d/%d] %s", attempt, max, retryCauseSummary(cause))));

        // Build the initial agent through a factory so the model can be swapped at runtime.
        AgentFactory agentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                List.of(permissionHook, new LoggingHook(), new ToolCallLoggingHook()), memory, interactiveRetry);
        AgentHolder agentHolder = new AgentHolder(agentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attach(agentHolder, agentFactory, defaultModel.id());
        System.out.println(Ansi.success("Model: ") + Ansi.info(defaultModel.label()));

        // Multi-agent registry: the default agent (built above) is instance "default" and active;
        // additional saved agents (workspace/agents/) are built per-spec with their own model /
        // tool subset / permission mode. AgentHolder stays a live view of the active instance, so
        // every existing reader (REPL/session/channel/compression) is unaffected.
        AgentRegistry agentRegistry = new AgentRegistry(agentHolder);
        AgentSpec defaultSpec = new AgentSpec("default", config.getAgent().getName(), sysPrompt,
                List.of(), null, defaultModel.id(), config.getAgent().getMaxIters());
        agentRegistry.register(new AgentInstance("default", defaultSpec, agentHolder.get()));

        AgentInstanceFactory agentInstanceFactory = new AgentInstanceFactory(
                spec -> {
                    Model baseModel = modelManager.modelFor(spec.modelId());
                    return interactiveRetry == null ? baseModel
                            : new io.pigagent.core.retry.RetryingModel(baseModel, interactiveRetry);
                },
                spec -> AgentWiring.toolkitFor(toolkit, spec.toolNames()),
                spec -> List.of(
                        new ToolPermissionHook(() -> configManager.getConfig().getPermissions(),
                                permissionConfirmer, allowlistWriter, false,
                                () -> AgentWiring.permissionModeOf(spec.permissionMode())),
                        new LoggingHook(), new ToolCallLoggingHook()),
                memory);
        AgentSpecRepository agentRepository = new AgentSpecRepository(workspace.getAgentsDir());
        for (AgentSpec s : agentRepository.findAll()) {
            if (!"default".equals(s.id())) {
                try {
                    agentRegistry.register(agentInstanceFactory.create(s));
                } catch (Exception e) {
                    System.err.println(Ansi.warn("[Agent] Failed to load '" + s.id() + "': " + e.getMessage()));
                }
            }
        }

        // Digital employee: agents with a schedule run their mandate unattended via a scheduled
        // AgentRunner. Each run uses an ISOLATED one-shot agent (never the active instance),
        // fail-closed permissions merged with the agent's commandAllowlist, and denied dangerous
        // actions feed the report's「等你决定」. Reports land in workspace/reports/; lastRunAt is saved.
        AgentRunner.AgentBuilder autonomousBuilder = (spec, recorder) -> {
            Model runModel = new io.pigagent.core.retry.RetryingModel(
                    modelManager.modelFor(spec.modelId()), interactiveRetry);
            ToolPermissionHook unattended = new ToolPermissionHook(
                    () -> mergedPermissionConfig(configManager.getConfig().getPermissions(),
                            spec.commandAllowlist()),
                    null, null, false,
                    () -> AgentWiring.permissionModeOf(spec.permissionMode()),
                    recorder::record);
            return PigAgent.builder()
                    .name(spec.name()).sysPrompt(spec.sysPrompt())
                    .model(runModel)
                    .toolkit(AgentWiring.toolkitFor(toolkit, spec.toolNames()))
                    .hooks(List.of(unattended, new LoggingHook(), new ToolCallLoggingHook()))
                    .build();
        };
        AgentRunner agentRunner = new AgentRunner(
                autonomousBuilder,
                new FileReportWriter(workspace.getReportsDir()),
                (spec, epoch) -> agentRepository.save(spec.withLastRunAtEpochMs(epoch)),
                null);
        // The kernel façade: the CLI (and future Web) drive agent management through this, not the
        // internal registry/repository/factory/runner directly.
        AgentKernel agentKernel = new AgentKernel(
                agentRegistry, agentRepository, agentInstanceFactory, agentRunner);
        for (AgentSpec s : agentRegistry.list().stream().map(i -> i.spec()).toList()) {
            if (s.isAutonomous()) {
                taskScheduler.schedule("agent:" + s.id(), TaskSchedule.cron(s.schedule()),
                        () -> agentRunner.run(s));
                System.out.println(Ansi.success("Digital employee scheduled: ")
                        + Ansi.info(s.name() + " [" + s.schedule() + "]"));
            }
        }

        // Channels get their own agent whose permission hook runs in channel mode (uses
        // permissions.channel-mode, no interactive confirmer → ASK fails closed). attachChannel
        // makes model switches rebuild it too so channels keep following the active model.
        ToolPermissionHook channelPermissionHook = new ToolPermissionHook(
                () -> configManager.getConfig().getPermissions(), null, null, true);
        AgentFactory channelAgentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                List.of(channelPermissionHook, new LoggingHook(), new ToolCallLoggingHook()), memory, channelRetry);
        AgentHolder channelAgentHolder = new AgentHolder(
                channelAgentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attachChannel(channelAgentHolder, channelAgentFactory);

        // Session management: AgentScope's JsonSession persists each session's conversation;
        // our repository tracks listing metadata. initialize() restores the last active session.
        JsonSession agentSession = new JsonSession(workspace.getSessionsDir());
        SessionRepository sessionRepository = new FileSystemSessionRepository(workspace.getSessionsDir());
        SessionManager sessionManager = new SessionManager(
                agentHolder, modelManager, agentSession, memory, sessionRepository,
                configManager, workspace.getSessionsDir());
        sessionManager.initialize();
        sessionManager.getCurrentSession().ifPresent(s ->
                System.out.println(Ansi.success("Session: ") + Ansi.info(s.name() + " [" + s.id() + "]")));

        PigAgentConfig.CompressionConfig comp = config.getCompression();
        CompressionService compressionService = new CompressionService(
                agentHolder, comp.getMaxContextTokens(), comp.getThreshold(), comp.isEnabled());

        List<ChannelAgentBridge> bridges = startChannels(channelAgentHolder, agentKernel, config.getChannels());

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

        new AgentRepl(agentHolder, agentKernel, workspace.getReportsDir(),
                configManager, registry, modelManager, compressionService,
                mcpManager, bridges, sessionManager, workspace.getRootPath(), readerRef).run();
    }

    /** A permission config whose command allowlist merges the global list with an agent's own
     *  {@code commandAllowlist}, so an unattended run may execute the few commands it declares. */
    private static PigAgentConfig.PermissionConfig mergedPermissionConfig(
            PigAgentConfig.PermissionConfig base, List<String> extraCommands) {
        PigAgentConfig.PermissionConfig cfg = new PigAgentConfig.PermissionConfig();
        cfg.setMode(base.getMode());
        cfg.setChannelMode(base.getChannelMode());
        cfg.setToolOverrides(base.getToolOverrides());
        PigAgentConfig.PermissionConfig.Allowlist al = new PigAgentConfig.PermissionConfig.Allowlist();
        List<String> commands = new ArrayList<>(base.getAllowlist().getCommands());
        if (extraCommands != null) {
            commands.addAll(extraCommands);
        }
        al.setCommands(commands);
        al.setTools(base.getAllowlist().getTools());
        cfg.setAllowlist(al);
        return cfg;
    }

    /** One-line, length-bounded summary of a retry cause for the visible retry notice. */
    private static String retryCauseSummary(Throwable cause) {
        if (cause == null) {
            return "transient error";
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        String oneLine = msg.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
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
                    System.err.println(Ansi.warn("[Channel] Unknown channel type: " + id + ", skipping"));
                    yield null;
                }
            };
            if (channel == null) continue;

            ChannelAgentBridge bridge = new ChannelAgentBridge(agentHolder, channel, agentKernel);
            bridge.start();
            bridges.add(bridge);
            System.out.println(Ansi.success("Channel started: ") + Ansi.info(channel.displayName()));
        }
        return bridges;
    }
}
