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
import io.pigagent.core.compression.BudgetRatios;
import io.pigagent.core.compression.CompressionService;
import io.pigagent.core.compression.EngineeringOptions;
import io.pigagent.core.interrupt.InterruptController;
import io.pigagent.core.interrupt.InterruptibleModel;
import io.pigagent.core.hook.LoggingHook;
import io.pigagent.core.hook.ToolCallLoggingHook;
import io.pigagent.core.loop.LoopDetectionHook;
import io.pigagent.core.loop.LoopDetector;
import io.pigagent.core.memory.CompositeLongTermMemory;
import io.pigagent.core.memory.FileSystemLongTermMemory;
import io.pigagent.core.memory.extraction.AsyncMemoryExtractionScheduler;
import io.pigagent.core.memory.extraction.ConfidenceGate;
import io.pigagent.core.memory.extraction.ExtractingLongTermMemory;
import io.pigagent.core.memory.extraction.FactMerger;
import io.pigagent.core.memory.extraction.LlmMemoryExtractor;
import io.pigagent.core.memory.extraction.MarkdownFactStore;
import io.pigagent.core.memory.extraction.MemoryExtractionPipeline;
import io.pigagent.core.memory.extraction.MemoryExtractor;
import io.pigagent.core.memory.extraction.MemoryNoiseFilter;
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
import io.pigagent.session.SessionMemoryFactory;
import io.pigagent.session.SessionRepository;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskSchedule;
import io.pigagent.task.TaskScheduler;
import io.pigagent.tool.contract.ToolContractGuard;
import io.pigagent.tool.deferred.DeferralPlan;
import io.pigagent.tool.deferred.DeferredToolGate;
import io.pigagent.tool.deferred.DeferredToolPlanner;
import io.pigagent.tool.deferred.DeferredToolRegistry;
import io.pigagent.tool.deferred.DeferredToolReveal;
import io.pigagent.tool.deferred.ToolInfo;
import io.pigagent.tool.deferred.ToolSearchTool;
import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.loop.LoopDetectedTool;
import io.pigagent.tool.mcp.McpConfirmer;
import io.pigagent.tool.mcp.McpTool;
import io.pigagent.tool.permission.AllowlistWriter;
import io.pigagent.tool.permission.PermissionConfirmer;
import io.pigagent.tool.permission.PermissionDeniedTool;
import io.pigagent.tool.permission.ToolPermissionHook;
import io.pigagent.tool.sandbox.SandboxPolicy;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.skills.SkillsTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolRegistrar;
import io.pigagent.tool.availability.ToolAvailabilityGate;
import io.pigagent.tool.availability.ToolAvailabilityReport;
import io.pigagent.tool.task.TaskTool;
import io.pigagent.workspace.WorkspaceManager;
import org.jline.reader.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    /**
     * Fixed tool guidance appended to the (user-editable) {@code AGENT.md} + {@code INFO.md} when the
     * system prompt is assembled ({@code readAgentMd() + readInfoMd() + TOOL_GUIDANCE}). Unlike
     * {@code AGENT.md} (which a user may rewrite), this block ships with the release and therefore
     * carries the always-on operational constraints that must hold regardless of prompt edits:
     * native-path file tools instead of the shell (writeFile is WRITE → allowed in {@code auto};
     * shell is EXEC → still confirmed; native paths avoid WSL's /mnt/c on Windows), graceful
     * degradation when a tool is confirmed/denied/unavailable, and never echoing credentials. It is a
     * constant, so the assembled system prompt stays byte-stable per run (prefix-cache friendly).
     */
    static final String TOOL_GUIDANCE = """

            ## Tooling & safety (always applies)
            - File CRUD: ALWAYS use the writeFile / readFile / listDirectory tools with a native\
             absolute path (Windows e.g. C:\\Users\\you\\file.txt, Unix e.g. /home/you/file.txt).\
             NEVER use executeCommand (the shell) to create, read or edit files — use executeCommand\
             only to run programs/commands.
            - Some tools are mutating, run commands, access the network or administer MCP servers; they\
             may require user confirmation or be denied by the active permission mode, and some tools\
             (e.g. webSearch) may be unavailable. If a call is denied or a tool is missing, do NOT retry\
             in a loop or bypass the safeguard — adapt or ask.
            - Never print secrets, API keys or tokens in your output; credential files are off-limits.
            """;

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
        /** Debounce scheduler for memory extraction; null when extraction is disabled. */
        private final AutoCloseable memoryExtractionScheduler;

        private Services(WorkspaceManager workspace, ConfigurationManager configManager, PigAgentConfig config,
                         ProtocolRegistry registry, ModelManager modelManager, TaskManager taskManager,
                         McpManager mcpManager, AgentHolder agentHolder, AgentHolder channelAgentHolder,
                         AgentKernel agentKernel, SessionManager sessionManager,
                         CompressionService compressionService, ToolAvailabilityReport availabilityReport,
                         AtomicReference<LineReader> readerRef,
                         JsonSession agentSession, TaskScheduler taskScheduler,
                         AutoCloseable memoryExtractionScheduler) {
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
            this.memoryExtractionScheduler = memoryExtractionScheduler;
        }

        /** Release the shared resources — call from the frontend's shutdown hook. */
        public void shutdownCommon() {
            sessionManager.saveCurrent();
            // Flush + stop the memory-extraction scheduler BEFORE closing the session store, so a
            // last pending extraction lands (no leak, no lost work). No-op when extraction is off.
            closeMemoryExtractionScheduler();
            agentSession.close();
            taskScheduler.shutdown();
            mcpManager.closeAll();
        }

        private void closeMemoryExtractionScheduler() {
            if (memoryExtractionScheduler == null) {
                return;
            }
            try {
                memoryExtractionScheduler.close();
            } catch (Exception e) {
                log.warn("Failed to close memory-extraction scheduler: {}", e.getMessage());
            }
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
        // Command-execution sandbox (exec-sandbox): build a conservative, config-backed policy and
        // inject it into ShellTools via ToolContext (mirrors how FileSystemTools gets workspaceRoot).
        PigAgentConfig.ExecSandboxConfig execCfg = config.getSandbox().getExec();
        SandboxPolicy sandboxPolicy = new SandboxPolicy(
                execCfg.getMaxOutputBytes(), execCfg.getTimeoutSeconds(),
                execCfg.getDenylist(), execCfg.getWarnlist(), execCfg.isScrubEnv(), execCfg.getWorkingDir());
        ToolContext toolContext = new ToolContext(taskManager, workspace.getSkillsDir(),
                workspace.getRootPath(), config.getTools().getWeb().getAllowedHosts(), sandboxPolicy);
        List<Object> builtinTools;
        if (Boolean.parseBoolean(System.getProperty(TOOLS_AUTO_REGISTER_PROP, "true"))) {
            ToolRegistrar.Result reg = ToolRegistrar.registerAll(toolkit, toolContext, List.of());
            log.info("Tools auto-registered: {}", reg.registered);
            builtinTools = reg.instances;
        } else {
            log.info("Tool auto-register disabled — using manual registration (fallback)");
            // Core tools only (tools-core-slim). The extracted web/checklist tools are contributed by
            // pig-agent-plugin-builtin via PluginRegistry below, which runs regardless of this flag.
            builtinTools = List.of(
                    new TaskTool(taskManager),
                    new ShellTools(sandboxPolicy),
                    new FileSystemTools(),
                    new SkillsTool(workspace.getSkillsDir()),
                    new PermissionDeniedTool(),
                    new LoopDetectedTool());
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

        // Deferred tools (deferred-tools): keep large/rarely-used tool schemas OUT of the model's
        // initial tool list; the model discovers + reveals them on demand via `tool_search`. Enabled
        // by config (default off → nothing here happens; full backward compat). The registry is
        // shared: tool_search is built now (so the guard below wraps it and it never re-registers
        // MCP tools) with the SAME empty registry the gate populates after MCP attaches; the reveal
        // seam flips group active-state on this shared toolkit. MCP tools are grouped at attach time
        // (namer below) so deferral is MCP-safe (never re-registers MCP tools → mcpClientName kept).
        PigAgentConfig.DeferredToolsConfig deferredCfg = config.getTools().getDeferred();
        boolean deferredEnabled = deferredCfg.isEnabled();
        DeferredToolRegistry deferredRegistry = new DeferredToolRegistry();
        if (deferredEnabled) {
            DeferredToolReveal reveal = DeferredToolGate.reveal(toolkit, deferredRegistry);
            toolkit.registration().tool(new ToolSearchTool(deferredRegistry, reveal)).apply();
            mcpManager.setToolGroupNamer(name -> "mcp:" + name);
        }

        // Dispatch-layer contract guard (tool-json-contract): wrap every built-in tool so any
        // exception a tool lets escape becomes a canonical {"error":...} result instead of aborting
        // the turn. Installed after the built-in tools + McpTool (+ tool_search) but BEFORE MCP
        // servers attach, so it never re-registers (and thus never breaks the identity/mcpClientName
        // of) live MCP-server tools, which AgentScope manages separately. Per-agent toolkits built
        // via Toolkit.copy() inherit the guarded built-in tools.
        ToolContractGuard.install(toolkit);

        mcpManager.initialize(new JsonMcpStore(workspace.getMcpFile()), toolkit, config.getMcp());

        // Now that all tools (built-in + plugin + MCP) are registered, decide + apply deferral:
        // build the inventory from the live schemas (all active here), tag MCP tools with their
        // attach-time group, plan (explicit list ∪ threshold rule), then hide the deferred ones.
        if (deferredEnabled) {
            List<ToolInfo> inventory = buildToolInventory(toolkit, mcpManager);
            DeferralPlan plan = DeferredToolPlanner.plan(true, deferredCfg.getTools(),
                    deferredCfg.isAutoDeferMcp(), deferredCfg.getThreshold(), inventory);
            DeferredToolGate.applyTo(toolkit, plan, inventory, deferredRegistry);
            log.info("Deferred tools: {} hidden from initial schema (searchable via tool_search)",
                    deferredRegistry.deferredNames().size());
        }

        // Fixed tool guidance (TOOL_GUIDANCE) appended to the (user-editable) AGENT.md + INFO.md. See
        // the constant's javadoc: it carries the always-on constraints (native-path file tools over
        // the shell, graceful degradation on denied/unavailable tools, never echo credentials).
        String sysPrompt = workspace.readAgentMd() + "\n\n" + workspace.readInfoMd() + TOOL_GUIDANCE;

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
        // Loop detection (loop-detection): after the permission veto (priority()=0) so it never
        // interferes; STOP reuses the veto-to-sentinel mechanism, WARN reuses the ephemeral
        // PreReasoning injection. Its own detector instance (per-agent), reset per turn by the hook.
        interactiveHooks.add(newLoopDetectionHook(configManager));
        interactiveHooks.add(new LoggingHook());
        interactiveHooks.add(new ToolCallLoggingHook());
        interactiveHooks.addAll(pluginResult.hooks);
        AgentFactory agentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                interactiveHooks, memory,
                interactiveRetry, interruptController, config.getAgent().getMaxIters());
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
                    .maxIters(spec.maxIters())
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
                List.of(channelPermissionHook, newLoopDetectionHook(configManager),
                        new LoggingHook(), new ToolCallLoggingHook()), memory,
                channelRetry, null, config.getAgent().getMaxIters());
        AgentHolder channelAgentHolder = new AgentHolder(
                channelAgentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attachChannel(channelAgentHolder, channelAgentFactory);

        JsonSession agentSession = new JsonSession(workspace.getSessionsDir());
        SessionRepository sessionRepository = new FileSystemSessionRepository(workspace.getSessionsDir());

        // Memory extraction (memory-extraction): when enabled, the per-session temp memory is wrapped
        // with an ExtractingLongTermMemory decorator that LLM-extracts classified facts off the turn's
        // critical path (async, debounced). Default off → DEFAULT factory = raw FileSystemLongTermMemory,
        // no scheduler, byte-for-byte the pre-extraction behavior. Session-tier only; global tier and
        // the PreReasoning injection half are untouched.
        PigAgentConfig.MemoryExtractionConfig extractionCfg = config.getMemory().getExtraction();
        AsyncMemoryExtractionScheduler extractionScheduler = null;
        SessionMemoryFactory sessionMemoryFactory = SessionMemoryFactory.DEFAULT;
        if (extractionCfg.isEnabled()) {
            extractionScheduler = new AsyncMemoryExtractionScheduler(extractionCfg.getDebounceMs());
            // Shared, stateless collaborators; only the FactStore/pipeline are per-session (path-bound).
            MemoryExtractor extractor = new LlmMemoryExtractor(() -> agentHolder.get().getModel());
            MemoryNoiseFilter noiseFilter = new MemoryNoiseFilter();
            ConfidenceGate gate = new ConfidenceGate();
            FactMerger merger = new FactMerger();
            AsyncMemoryExtractionScheduler scheduler = extractionScheduler;
            java.util.function.BooleanSupplier enabled =
                    () -> configManager.getConfig().getMemory().getExtraction().isEnabled();
            java.util.function.DoubleSupplier threshold =
                    () -> configManager.getConfig().getMemory().getExtraction().getConfidenceThreshold();
            sessionMemoryFactory = tempFile -> {
                FileSystemLongTermMemory raw = new FileSystemLongTermMemory(tempFile);
                MemoryExtractionPipeline pipeline = new MemoryExtractionPipeline(
                        noiseFilter, extractor, gate, merger, new MarkdownFactStore(tempFile), threshold);
                return new ExtractingLongTermMemory(raw, pipeline, scheduler, enabled);
            };
            log.info("Memory extraction enabled (threshold {}, debounce {}ms)",
                    extractionCfg.getConfidenceThreshold(), extractionCfg.getDebounceMs());
        }

        SessionManager sessionManager = new SessionManager(
                agentHolder, modelManager, agentSession, memory, sessionRepository,
                configManager, workspace.getSessionsDir(), sessionMemoryFactory);
        sessionManager.initialize();
        sessionManager.getCurrentSession().ifPresent(s ->
                log.info("Session: {} [{}]", s.name(), s.id()));

        PigAgentConfig.CompressionConfig comp = config.getCompression();
        EngineeringOptions engineeringOptions = new EngineeringOptions(
                comp.isRecursiveSummary(), comp.getMaxSummaryDepth(),
                comp.isImportanceRetention(), comp.isVerbatimProtection(), comp.isConsistencyCheck(),
                comp.getKeepRecent(),
                new BudgetRatios(comp.getPinnedRatio(), comp.getRecentRatio(), comp.getSummarizedRatio()));
        CompressionService compressionService = new CompressionService(
                agentHolder, comp.getMaxContextTokens(), comp.getThreshold(), comp.isEnabled(),
                new SessionLineageWriter(sessionRepository), engineeringOptions);

        return new Services(workspace, configManager, config, registry, modelManager, taskManager, mcpManager,
                agentHolder, channelAgentHolder, agentKernel, sessionManager, compressionService,
                availabilityReport, readerRef, agentSession, taskScheduler, extractionScheduler);
    }

    /**
     * Build a fresh {@link LoopDetectionHook} (with its own {@link LoopDetector} instance, so each
     * agent's window is independent). Thresholds are read from config once here; {@code enabled} is
     * read live via a supplier so {@code loop-detection.enabled=false} bypasses without a restart.
     * The ignore set holds the two veto sentinels so denied/looped calls are never re-counted.
     */
    static LoopDetectionHook newLoopDetectionHook(ConfigurationManager configManager) {
        PigAgentConfig.LoopDetectionConfig lc = configManager.getConfig().getLoopDetection();
        LoopDetector detector = new LoopDetector(
                lc.getWindowSize(), lc.getWarnThreshold(), lc.getStopThreshold());
        return new LoopDetectionHook(
                detector,
                () -> configManager.getConfig().getLoopDetection().isEnabled(),
                Set.of(LoopDetectionHook.SENTINEL_TOOL_NAME, PermissionDeniedTool.TOOL_NAME));
    }

    /**
     * Build the tool inventory for the deferral planner/gate from the live toolkit: one
     * {@link ToolInfo} per registered tool (name + description from its schema), tagging MCP tools
     * with the attach-time group ({@code "mcp:<server>"}) they live in — that tag both marks them as
     * MCP and names the group the gate deactivates to defer them. Reads only in-memory group state
     * (no MCP network calls).
     */
    static List<ToolInfo> buildToolInventory(Toolkit toolkit, McpManager mcpManager) {
        java.util.Map<String, String> mcpToolGroup = new java.util.HashMap<>();
        for (String group : mcpManager.managedToolGroups()) {
            io.agentscope.core.tool.ToolGroup tg = toolkit.getToolGroup(group);
            if (tg != null) {
                for (String toolName : tg.getTools()) {
                    mcpToolGroup.put(toolName, group);
                }
            }
        }
        List<ToolInfo> inventory = new ArrayList<>();
        for (io.agentscope.core.model.ToolSchema schema : toolkit.getToolSchemas()) {
            inventory.add(ToolInfo.of(schema.getName(), schema.getDescription(),
                    mcpToolGroup.get(schema.getName())));
        }
        return inventory;
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
