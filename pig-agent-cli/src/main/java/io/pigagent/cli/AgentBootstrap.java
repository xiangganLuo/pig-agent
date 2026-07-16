package io.pigagent.cli;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
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
import io.pigagent.core.middleware.LoggingMiddleware;
import io.pigagent.core.middleware.ToolCallLoggingMiddleware;
import io.pigagent.core.loop.LoopDetectionMiddleware;
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
import io.pigagent.tool.permission.PermissionContextFactory;
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
import java.util.function.Supplier;

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
        private final TaskScheduler taskScheduler;
        /** Debounce scheduler for memory extraction; null when extraction is disabled. */
        private final AutoCloseable memoryExtractionScheduler;

        private Services(WorkspaceManager workspace, ConfigurationManager configManager, PigAgentConfig config,
                         ProtocolRegistry registry, ModelManager modelManager, TaskManager taskManager,
                         McpManager mcpManager, AgentHolder agentHolder, AgentHolder channelAgentHolder,
                         AgentKernel agentKernel, SessionManager sessionManager,
                         CompressionService compressionService, ToolAvailabilityReport availabilityReport,
                         AtomicReference<LineReader> readerRef,
                         TaskScheduler taskScheduler,
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
            this.taskScheduler = taskScheduler;
            this.memoryExtractionScheduler = memoryExtractionScheduler;
        }

        /** Release the shared resources — call from the frontend's shutdown hook. */
        public void shutdownCommon() {
            sessionManager.saveCurrent();
            // Flush + stop the memory-extraction scheduler BEFORE final persistence, so a last
            // pending extraction lands (no leak, no lost work). No-op when extraction is off.
            closeMemoryExtractionScheduler();
            // av2 Phase 3/4: conversation state now persists automatically via the native
            // AgentStateStore per turn (no JsonSession to close); saveCurrent() flushed the active slot.
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

        // av2 Phase 3/4: ONE shared native state store rooted at workspace/state/ (kept apart from the
        // metadata sidecar under workspace/sessions/{id}/). Passed to every rebuilt agent so per-session
        // conversation survives model switches + restarts (§10 Phase-3 store seam).
        AgentStateStore stateStore = new JsonFileAgentStateStore(workspace.getRootPath().resolve("state"));

        // M-1 (interim): command-granular allowlist isn't mapped to native per-tool rules yet
        // (PermissionContextFactory reads allowlist.tools only). Warn so an operator relying on
        // allowlist.commands knows those entries still require re-confirmation (fail-closed, safe).
        int allowCmdCount = config.getPermissions().getAllowlist().getCommands().size();
        if (allowCmdCount > 0) {
            log.warn("permissions.allowlist.commands has {} entr{}, but command-granular allowlisting "
                    + "is not yet wired to native permission (Phase-5); those commands are still confirmed.",
                    allowCmdCount, allowCmdCount == 1 ? "y" : "ies");
        }

        // Native permission context (av2 Phase 4) — the 2.0 replacement for ToolPermissionHook. Built
        // lazily from the CURRENT mode + the toolkit's tool names on each agent (re)build, so a
        // model-switch rebuild (and an /mcp add rebuild, M-2) picks up the active mode + new tools.
        // interactive=true → ASK routes to HITL (RequireUserConfirmEvent, handled by AgentRepl);
        // the channel + autonomous suppliers pass interactive=false (no confirmer → ASK fail-closed).
        Supplier<PermissionContextState> interactivePermCtx = () -> PermissionContextFactory.build(
                configManager.getConfig().getPermissions(),
                configManager.getConfig().getPermissions().resolveMode(),
                toolkit.getToolNames(), true);

        // av2 Phase 5a: native model-call retry. The self-built RetryingModel/RetryPolicy/
        // TransientErrorClassifier are gone — AgentScope's ExecutionConfig retry already filters
        // transient (429/5xx/timeout/IO) from permanent (4xx/auth) errors with exponential backoff,
        // applied at the model layer (inside a single agent invocation). We map pig's config
        // model.retry to ReActAgent.Builder.maxRetries: enabled → the configured count; disabled → 1
        // (a single attempt, no retries). Native has no per-retry callback, so the inline "[retry k/N]"
        // notice and the (unsafe-by-default) client-side per-attempt timeout are not carried over.
        PigAgentConfig.RetryConfig rc = config.getModel().getRetry();
        int maxRetries = rc.isEnabled() ? Math.max(1, rc.getMaxRetries()) : 1;

        // One shared interrupt controller, owned by the kernel (av2 Phase 5a). The kernel registers a
        // per-turn handle whose interrupt action calls native ReActAgent.interrupt; the model is no
        // longer decorated. Autonomous/channel tracks don't share it (their turns aren't kernel-driven).
        InterruptController interruptController = new InterruptController();

        // Interactive agent middlewares (av2 Phase 5a: native MiddlewareBase, no more legacy hooks):
        // the fixed pig middlewares + any middlewares contributed by plugins (change plugin-system).
        // Permission is NOT a middleware — it is the native PermissionContextState on the builder
        // (interactivePermCtx above). Loop-detection MUST come before the ephemeral-memory middleware
        // (appended last inside PigAgent.build) so it counts raw user messages before memory injection.
        List<MiddlewareBase> interactiveMiddlewares = new ArrayList<>();
        // Loop detection (loop-detection): its own detector instance (per-agent), reset per turn by the
        // middleware; STOP rewrites the acting call to the loop sentinel, WARN reuses ephemeral injection.
        interactiveMiddlewares.add(newLoopDetectionMiddleware(configManager));
        interactiveMiddlewares.add(new LoggingMiddleware());
        interactiveMiddlewares.add(new ToolCallLoggingMiddleware());
        interactiveMiddlewares.addAll(pluginResult.middlewares);
        AgentFactory agentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                interactiveMiddlewares, memory,
                maxRetries, null, config.getAgent().getMaxIters(),
                stateStore, interactivePermCtx);
        AgentHolder agentHolder = new AgentHolder(agentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attach(agentHolder, agentFactory, defaultModel.id());
        log.info("Model: {}", defaultModel.label());

        AgentRegistry agentRegistry = new AgentRegistry(agentHolder);
        AgentSpec defaultSpec = new AgentSpec("default", config.getAgent().getName(), sysPrompt,
                List.of(), null, defaultModel.id(), config.getAgent().getMaxIters());
        agentRegistry.register(new AgentInstance("default", defaultSpec, agentHolder.get()));

        AgentInstanceFactory agentInstanceFactory = new AgentInstanceFactory(
                // av2 Phase 5a: the model is passed through untouched — retry + interrupt are native
                // (maxRetries below; ReActAgent.interrupt driven by the kernel). No Model decorators.
                spec -> modelManager.modelFor(spec.modelId()),
                spec -> AgentWiring.toolkitFor(toolkit, spec.toolNames()),
                // av2 Phase 4/5a: permission is native (context provider below); per-agent middlewares
                // are logging-only (loop detection is instance-stateful → interactive/channel tracks).
                spec -> List.of(new LoggingMiddleware(), new ToolCallLoggingMiddleware()),
                memory,
                stateStore,
                // Per-agent native permission context: the agent's permissionMode override (else the
                // global mode) mapped over its own tool subset. Interactive track → ASK routes to HITL.
                (spec, tk) -> PermissionContextFactory.build(
                        configManager.getConfig().getPermissions(),
                        AgentWiring.effectiveMode(spec.permissionMode(),
                                configManager.getConfig().getPermissions().resolveMode()),
                        tk.getToolNames(), true),
                maxRetries);
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

        // NB: the autonomous (digital-employee) track is deliberately NOT wired to the kernel's
        // interrupt controller (which is scoped to the interactive turn); its timeout stays
        // best-effort. Retry is native (maxRetries on the builder) — no Model decorator (av2 5a).
        AgentRunner.AgentBuilder autonomousBuilder = (spec, recorder) -> {
            Model runModel = modelManager.modelFor(spec.modelId());
            Toolkit runToolkit = AgentWiring.toolkitFor(toolkit, spec.toolNames());
            // av2 Phase 4: unattended → interactive=false, so ASK fail-closes to DENY (DONT_ASK base).
            // The recorder is populated post-hoc by AgentRunner (scanning DENIED tool-results), since
            // native permission has no build-time denial callback. mergedPermissionConfig still folds
            // the agent's commandAllowlist into the base allowlist (tools-level rules take effect).
            PermissionContextState autoCtx = PermissionContextFactory.build(
                    mergedPermissionConfig(configManager.getConfig().getPermissions(), spec.commandAllowlist()),
                    AgentWiring.effectiveMode(spec.permissionMode(),
                            configManager.getConfig().getPermissions().resolveMode()),
                    runToolkit.getToolNames(), false);
            return PigAgent.builder()
                    .name(spec.name()).sysPrompt(spec.sysPrompt())
                    .model(runModel)
                    .toolkit(runToolkit)
                    .middlewares(List.of(new LoggingMiddleware(), new ToolCallLoggingMiddleware()))
                    .maxIters(spec.maxIters())
                    .maxRetries(maxRetries)
                    .permissionContext(autoCtx)
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

        // A separate channel agent (channel-mode native permission, no confirmer → ASK fail-closed).
        // attachChannel rebuilds it on model switch so channels follow the active model. The CLI starts
        // the channel bridges, each threading its own channel-owned session id (Phase 4).
        Supplier<PermissionContextState> channelPermCtx = () -> PermissionContextFactory.build(
                configManager.getConfig().getPermissions(),
                configManager.getConfig().getPermissions().resolveChannelMode(),
                toolkit.getToolNames(), false);
        // The channel agent is a separate track (D4) with no stop key; it is deliberately NOT wired
        // to the interrupt controller (which is scoped to the interactive kernel turn). It shares the
        // one state store — channel conversations live in their own (pig, "channel:<id>") slots.
        AgentFactory channelAgentFactory = new AgentFactory(
                config.getAgent().getName(), sysPrompt, toolkit,
                List.of(newLoopDetectionMiddleware(configManager),
                        new LoggingMiddleware(), new ToolCallLoggingMiddleware()), memory,
                maxRetries, null, config.getAgent().getMaxIters(),
                stateStore, channelPermCtx);
        AgentHolder channelAgentHolder = new AgentHolder(
                channelAgentFactory.create(modelManager.buildModel(defaultModel)));
        modelManager.attachChannel(channelAgentHolder, channelAgentFactory);

        // M-2: on a runtime /mcp add|remove|enable|disable, rebuild the interactive + channel agents so
        // their native permission context re-snapshots the toolkit's new tool set (same mechanism as a
        // model switch). Fresh sessions/slots pick up per-tool rules for the new MCP tools; existing
        // slots stay fail-safe on the base mode (interactive DEFAULT→ASK, channel DONT_ASK→DENY). Fires
        // only for runtime changes (startup import in initialize() runs before this callback is set).
        mcpManager.setToolsChangedCallback(() -> modelManager.getCurrentModel().ifPresent(m -> {
            Model rebuilt = modelManager.buildModel(m);
            agentHolder.set(agentFactory.create(rebuilt));
            channelAgentHolder.set(channelAgentFactory.create(rebuilt));
            log.info("Rebuilt agents after MCP tool change (permission context re-snapshotted).");
        }));

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
                agentHolder, modelManager, memory, sessionRepository,
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
                availabilityReport, readerRef, taskScheduler, extractionScheduler);
    }

    /**
     * Build a fresh {@link LoopDetectionMiddleware} (with its own {@link LoopDetector} instance, so
     * each agent's window is independent). Thresholds are read from config once here; {@code enabled}
     * is read live via a supplier so {@code loop-detection.enabled=false} bypasses without a restart.
     * The ignore set holds the loop-detection sentinel so looped calls are never re-counted.
     *
     * <p>av2 Phase 5a: ported from the legacy hook to a native {@code onActing}/{@code onReasoning}
     * middleware. The permission-denied sentinel is gone (native permission denies before execution),
     * so only the loop sentinel remains in the ignore set.
     */
    static LoopDetectionMiddleware newLoopDetectionMiddleware(ConfigurationManager configManager) {
        PigAgentConfig.LoopDetectionConfig lc = configManager.getConfig().getLoopDetection();
        LoopDetector detector = new LoopDetector(
                lc.getWindowSize(), lc.getWarnThreshold(), lc.getStopThreshold());
        return new LoopDetectionMiddleware(
                detector,
                () -> configManager.getConfig().getLoopDetection().isEnabled(),
                Set.of(LoopDetectionMiddleware.SENTINEL_TOOL_NAME));
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
}
