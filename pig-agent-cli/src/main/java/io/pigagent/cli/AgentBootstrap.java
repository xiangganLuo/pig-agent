package io.pigagent.cli;

import io.agentscope.core.session.JsonSession;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
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
import io.pigagent.core.interrupt.InterruptController;
import io.pigagent.core.interrupt.InterruptibleModel;
import io.pigagent.core.hook.LoggingHook;
import io.pigagent.core.hook.ToolCallLoggingHook;
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;
import io.pigagent.core.retry.RetryPolicy;
import io.pigagent.core.retry.RetryingModel;
import io.pigagent.core.retry.TransientErrorClassifier;
import io.pigagent.mcp.JsonMcpStore;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.JsonModelStore;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.onboarding.OnboardingWizard;
import io.pigagent.plugin.DirectoryPluginSource;
import io.pigagent.plugin.PluginRegistry;
import io.pigagent.plugin.PluginSource;
import io.pigagent.plugin.ServiceLoaderPluginSource;
import io.pigagent.provider.anthropic.AnthropicProtocol;
import io.pigagent.provider.dashscope.DashScopeProtocol;
import io.pigagent.provider.gemini.GeminiProtocol;
import io.pigagent.provider.ollama.OllamaProtocol;
import io.pigagent.provider.openai.OpenAiProtocol;
import io.pigagent.provider.registry.ProtocolRegistry;
import io.pigagent.session.FileSystemSessionRepository;
import io.pigagent.session.SessionLineageWriter;
import io.pigagent.session.SessionManager;
import io.pigagent.session.SessionRepository;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskSchedule;
import io.pigagent.task.TaskScheduler;
import io.pigagent.tool.checklist.CheckListTool;
import io.pigagent.tool.contract.ToolContractGuard;
import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.mcp.McpConfirmer;
import io.pigagent.tool.mcp.McpTool;
import io.pigagent.tool.permission.AllowlistWriter;
import io.pigagent.tool.permission.PermissionConfirmer;
import io.pigagent.tool.permission.PermissionDeniedTool;
import io.pigagent.tool.permission.ToolPermissionHook;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolRegistrar;
import io.pigagent.tool.availability.ToolAvailabilityGate;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.task.TaskTool;
import io.pigagent.tool.webfetch.SmartWebFetchTool;
import io.pigagent.tool.websearch.BraveWebSearchTool;
import io.pigagent.workspace.WorkspaceManager;
import org.jline.reader.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the shared agent runtime (workspace, config, providers, model, tools, MCP, the agent +
 * kernel, sessions, compression, tasks, the channel agent, and digital-employee scheduling) — the
 * wiring the frontend depends on. It builds NO frontend: the CLI ({@link PigAgentCli}) adds the
 * REPL + channels on top.
 *
 * <p>Interactive confirmers read from {@code readerRef}, which the CLI wires to its JLine reader.
 * In a non-interactive process it stays null, so ASK-mode tool calls fail closed — switch to
 * {@code auto}/{@code bypass} (via {@code /permission}) to allow them.
 */
public final class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    /** System property to disable SPI tool auto-registration and fall back to pure-manual wiring. */
    static final String TOOLS_AUTO_REGISTER_PROP = "pigagent.tools.auto-register";

    private AgentBootstrap() {
    }

    /** All shared collaborators, plus the closeables the frontend's shutdown hook must release. */
    public static final class Services {
        public final WorkspaceManager workspace;
        public final ConfigurationManager configManager;
        public final PigAgentConfig config;
        public final ProtocolRegistry registry;
        public final ModelManager modelManager;
        public final TaskManager taskManager;
        public final McpManager mcpManager;
        public final AgentHolder agentHolder;
        public final AgentHolder channelAgentHolder;
        public final AgentKernel agentKernel;
        public final SessionManager sessionManager;
        public final CompressionService compressionService;
        public final ToolAvailabilityReport availabilityReport;
        public final AtomicReference<LineReader> readerRef;
        private final JsonSession agentSession;
        private final TaskScheduler taskScheduler;

        private Services(WorkspaceManager workspace, ConfigurationManager configManager, PigAgentConfig config,
                         ProtocolRegistry registry, ModelManager modelManager, TaskManager taskManager,
                         McpManager mcpManager, AgentHolder agentHolder, AgentHolder channelAgentHolder,
                         AgentKernel agentKernel, SessionManager sessionManager,
                         CompressionService compressionService, ToolAvailabilityReport availabilityReport,
                         AtomicReference<LineReader> readerRef,
                         JsonSession agentSession, TaskScheduler taskScheduler) {
            this.workspace = workspace;
            this.configManager = configManager;
            this.config = config;
            this.registry = registry;
            this.modelManager = modelManager;
            this.taskManager = taskManager;
            this.mcpManager = mcpManager;
            this.agentHolder = agentHolder;
            this.channelAgentHolder = channelAgentHolder;
            this.agentKernel = agentKernel;
            this.sessionManager = sessionManager;
            this.compressionService = compressionService;
            this.availabilityReport = availabilityReport;
            this.readerRef = readerRef;
            this.agentSession = agentSession;
            this.taskScheduler = taskScheduler;
        }

        /** Release the shared resources — call from the frontend's shutdown hook. */
        public void shutdownCommon() {
            sessionManager.saveCurrent();
            agentSession.close();
            taskScheduler.shutdown();
            mcpManager.closeAll();
        }
    }

    /**
     * Wire the full runtime.
     *
     * @param allowOnboarding when true and no model is configured, run the interactive onboarding
     *        wizard (CLI); when false, throw instead (the Web launcher can't onboard on a terminal).
     */
    public static Services build(boolean allowOnboarding) throws Exception {
        WorkspaceManager workspace = WorkspaceManager.defaultWorkspace();
        workspace.initialize();
        log.info("Workspace: {}", workspace.getRootPath().toAbsolutePath());

        ConfigurationManager configManager =
                new ConfigurationManager(workspace.getRootPath().resolve("application.yaml"));
        PigAgentConfig config = configManager.getConfig();

        ProtocolRegistry registry = new ProtocolRegistry();
        registry.register(new OpenAiProtocol());
        registry.register(new AnthropicProtocol());
        registry.register(new GeminiProtocol());
        registry.register(new OllamaProtocol());
        registry.register(new DashScopeProtocol());

        JsonModelStore modelStore = new JsonModelStore(workspace.getModelsFile());
        ModelManager modelManager = new ModelManager(registry, modelStore);
        if (!modelManager.isConfigured()) {
            if (!allowOnboarding) {
                throw new IllegalStateException(
                        "No model configured — run the CLI once (mvn exec:java -pl pig-agent-cli) to set one up.");
            }
            new OnboardingWizard(registry, modelManager).run();
        }
        StoredModel defaultModel = modelManager.getDefault()
                .orElseThrow(() -> new IllegalStateException("No model configured"));

        TaskManager taskManager = new TaskManager(new FileSystemTaskRepository(workspace.getTasksDir()));
        TaskScheduler taskScheduler = new TaskScheduler(taskManager);
        taskScheduler.scheduleAll();

        Toolkit toolkit = new Toolkit();
        // Builtin tools are auto-discovered via SPI (ServiceLoader, change tool-autoregister) — a new
        // tool is picked up by "dropping a file", no edit here. Set -Dpigagent.tools.auto-register=false
        // to fall back to the pure-manual registration below (legacy behavior). Either way we keep the
        // registered instances so the availability gate (tool-availability) can inspect them below.
        ToolContext toolContext = new ToolContext(taskManager, workspace.getSkillsDir(),
                workspace.getRootPath(), config.getTools().getWeb().getAllowedHosts());
        List<Object> builtinTools;
        if (Boolean.parseBoolean(System.getProperty(TOOLS_AUTO_REGISTER_PROP, "true"))) {
            ToolRegistrar.Result reg = ToolRegistrar.registerAll(toolkit, toolContext, List.of());
            log.info("Tools auto-registered: {}", reg.registered);
            builtinTools = reg.instances;
        } else {
            log.info("Tool auto-register disabled — using manual registration (fallback)");
            builtinTools = List.of(
                    new TaskTool(taskManager),
                    new ShellTools(),
                    new FileSystemTools(),
                    new SmartWebFetchTool(),
                    new BraveWebSearchTool(),
                    new CheckListTool(),
                    new SkillsTool(workspace.getSkillsDir()),
                    new PermissionDeniedTool());
            for (Object tool : builtinTools) {
                toolkit.registration().tool(tool).apply();
            }
        }

        // Plugins (change plugin-system): after built-in tool auto-registration, load external
        // plugins from the classpath (ServiceLoader<Plugin>) and workspace/plugins/*.jar, letting
        // each contribute tools + hooks via a single register(ctx) entrypoint. Plugin tools register
        // with the same first-wins de-dup as auto discovery (a built-in wins a name collision) and a
        // plugin that throws is isolated (fail-safe). No plugins → no-op (behavior unchanged). Runs
        // before the availability gate + ToolContractGuard so plugin tools get gated/guarded too.
        List<PluginSource> pluginSources = List.of(
                new ServiceLoaderPluginSource(),
                new DirectoryPluginSource(workspace.getPluginsDir()));
        PluginRegistry.Result pluginResult =
                PluginRegistry.loadAndRegister(pluginSources, toolContext, toolkit);
        List<Object> gatedTools = new ArrayList<>(builtinTools);
        gatedTools.addAll(pluginResult.toolInstances);

        // First-line availability filter: unavailable tools never enter the schema handed to the
        // model (prevents hallucinated calls, saves tokens). Orthogonal to the permission veto.
        ToolAvailabilityReport availabilityReport = ToolAvailabilityGate.applyTo(toolkit, gatedTools);

        McpManager mcpManager = new McpManager();

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

        // Dispatch-layer contract guard (tool-json-contract): wrap every built-in tool so any
        // exception a tool lets escape becomes a canonical {"error":...} result instead of aborting
        // the turn. Installed after the built-in tools + McpTool but BEFORE MCP servers attach, so
        // it never re-registers (and thus never breaks the identity/mcpClientName of) live MCP-server
        // tools, which AgentScope manages separately. Per-agent toolkits built via Toolkit.copy()
        // inherit the guarded built-in tools.
        ToolContractGuard.install(toolkit);

        mcpManager.initialize(new JsonMcpStore(workspace.getMcpFile()), toolkit, config.getMcp());

        // Fixed tool guidance appended to the (user-editable) AGENT.md + INFO.md — steers the agent to
        // the native-path file tools instead of the shell (writeFile is WRITE → allowed in `auto`;
        // shell is EXEC → still confirmed; and native paths avoid WSL's /mnt/c on Windows).
        String toolGuidance = """

                ## File operations (important)
                - To create, read, or list files, ALWAYS use the writeFile / readFile / listDirectory\
                 tools with a native absolute path (Windows e.g. C:\\Users\\you\\file.txt, Unix e.g.\
                 /home/you/file.txt).
                - Do NOT use executeCommand (the shell) to create or edit files. Use executeCommand\
                 only to run programs/commands, never for file CRUD.
                """;
        String sysPrompt = workspace.readAgentMd() + "\n\n" + workspace.readInfoMd() + toolGuidance;

        FileSystemLongTermMemory globalMemory = new FileSystemLongTermMemory(
                workspace.getContextDir().resolve("memory.md"));
        CompositeLongTermMemory memory = new CompositeLongTermMemory(globalMemory, config.isMemoryEnabled());

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

        PigAgentConfig.RetryConfig rc = config.getModel().getRetry();
        TransientErrorClassifier retryClassifier = new TransientErrorClassifier();
        RetryPolicy interactiveRetry = new RetryPolicy(
                rc.isEnabled(), rc.getMaxRetries(),
                java.time.Duration.ofSeconds(rc.getPerAttemptTimeoutSeconds()),
                java.time.Duration.ofMillis(rc.getFirstBackoffMs()),
                java.time.Duration.ofMillis(rc.getMaxBackoffMs()),
                retryClassifier,
                (attempt, max, cause, backoff) -> {
                    String msg = String.format("[retry %d/%d] %s, backing off %dms…",
                            attempt, max, retryCauseSummary(cause), backoff.toMillis());
                    LineReader r = readerRef.get();
                    if (r != null) { // REPL present: surface inline on the interactive terminal
                        r.getTerminal().writer().println(Ansi.warn(msg));
                        r.getTerminal().writer().flush();
                    } else {
                        log.warn(msg);
                    }
                });
        RetryPolicy channelRetry = new RetryPolicy(
                rc.isEnabled(), rc.getMaxRetries(),
                java.time.Duration.ofSeconds(rc.getPerAttemptTimeoutSeconds()),
                java.time.Duration.ofMillis(rc.getFirstBackoffMs()),
                java.time.Duration.ofMillis(rc.getMaxBackoffMs()),
                retryClassifier,
                (attempt, max, cause, backoff) -> log.warn("[channel retry {}/{}] {}",
                        attempt, max, retryCauseSummary(cause)));

        // One shared interrupt controller: the kernel registers/clears the current turn's handle and
        // the model decorators (built below) read it, so AgentKernel.interruptCurrent() cancels the
        // in-flight model call. Interactive, channel, per-agent and autonomous models all share it.
        InterruptController interruptController = new InterruptController();

        // Interactive agent hooks: the fixed pig hooks + any hooks contributed by plugins
        // (change plugin-system). Hooks are ordered by Hook.priority() at dispatch, so appending
        // plugin hooks at the end does not disturb the permission hook's priority()=0 precedence.
        List<Hook> interactiveHooks = new ArrayList<>();
        interactiveHooks.add(permissionHook);
        interactiveHooks.add(new LoggingHook());
        interactiveHooks.add(new ToolCallLoggingHook());
        interactiveHooks.addAll(pluginResult.hooks);
        AgentFactory agentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                interactiveHooks, memory,
                interactiveRetry, interruptController);
        AgentHolder agentHolder = new AgentHolder(agentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attach(agentHolder, agentFactory, defaultModel.id());
        log.info("Model: {}", defaultModel.label());

        AgentRegistry agentRegistry = new AgentRegistry(agentHolder);
        AgentSpec defaultSpec = new AgentSpec("default", config.getAgent().getName(), sysPrompt,
                List.of(), null, defaultModel.id(), config.getAgent().getMaxIters());
        agentRegistry.register(new AgentInstance("default", defaultSpec, agentHolder.get()));

        AgentInstanceFactory agentInstanceFactory = new AgentInstanceFactory(
                spec -> {
                    Model baseModel = new InterruptibleModel(modelManager.modelFor(spec.modelId()),
                            interruptController);
                    return interactiveRetry == null ? baseModel : new RetryingModel(baseModel, interactiveRetry);
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
                    log.warn("Failed to load agent '{}': {}", s.id(), e.getMessage());
                }
            }
        }

        // NB: the autonomous (digital-employee) model is deliberately NOT wrapped with
        // InterruptibleModel. Scheduled cron runs bypass the kernel, and the single-slot interrupt
        // controller is scoped to the interactive turn; sharing it across the autonomous track could
        // cross-talk with a concurrent interactive interrupt. Autonomous interrupt is out of scope
        // for interruptible-run (its timeout stays best-effort).
        AgentRunner.AgentBuilder autonomousBuilder = (spec, recorder) -> {
            Model runModel = new RetryingModel(modelManager.modelFor(spec.modelId()), interactiveRetry);
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
        AgentKernel agentKernel = new AgentKernel(
                agentRegistry, agentRepository, agentInstanceFactory, agentRunner, interruptController);
        for (AgentSpec s : agentRegistry.list().stream().map(AgentInstance::spec).toList()) {
            if (s.isAutonomous()) {
                taskScheduler.schedule("agent:" + s.id(), TaskSchedule.cron(s.schedule()),
                        () -> agentRunner.run(s));
                log.info("Digital employee scheduled: {} [{}]", s.name(), s.schedule());
            }
        }

        // A separate channel agent (channel-mode permissions, no confirmer). attachChannel rebuilds
        // it on model switch so channels follow the active model. The CLI starts the channel bridges.
        ToolPermissionHook channelPermissionHook = new ToolPermissionHook(
                () -> configManager.getConfig().getPermissions(), null, null, true);
        // The channel agent is a separate track (D4) with no stop key; it is deliberately NOT wired
        // to the interrupt controller (which is scoped to the interactive kernel turn).
        AgentFactory channelAgentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                List.of(channelPermissionHook, new LoggingHook(), new ToolCallLoggingHook()), memory, channelRetry);
        AgentHolder channelAgentHolder = new AgentHolder(
                channelAgentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attachChannel(channelAgentHolder, channelAgentFactory);

        JsonSession agentSession = new JsonSession(workspace.getSessionsDir());
        SessionRepository sessionRepository = new FileSystemSessionRepository(workspace.getSessionsDir());
        SessionManager sessionManager = new SessionManager(
                agentHolder, modelManager, agentSession, memory, sessionRepository,
                configManager, workspace.getSessionsDir());
        sessionManager.initialize();
        sessionManager.getCurrentSession().ifPresent(s ->
                log.info("Session: {} [{}]", s.name(), s.id()));

        PigAgentConfig.CompressionConfig comp = config.getCompression();
        CompressionService compressionService = new CompressionService(
                agentHolder, comp.getMaxContextTokens(), comp.getThreshold(), comp.isEnabled(),
                new SessionLineageWriter(sessionRepository));

        return new Services(workspace, configManager, config, registry, modelManager, taskManager, mcpManager,
                agentHolder, channelAgentHolder, agentKernel, sessionManager, compressionService,
                availabilityReport, readerRef, agentSession, taskScheduler);
    }

    /** A permission config whose command allowlist merges the global list with an agent's own
     *  {@code commandAllowlist}, so an unattended run may execute the few commands it declares. */
    static PigAgentConfig.PermissionConfig mergedPermissionConfig(
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
    static String retryCauseSummary(Throwable cause) {
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
}
