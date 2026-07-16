package io.pigagent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Root application config. Unknown/unrecognized fields are ignored rather than failing the whole
 * load and reverting to all-defaults — so config schema drift (a new-version field read by an old
 * build, or a stale field left in the file) never silently discards the user's recognized settings.
 * Tolerance is enforced mapper-level in {@code ConfigurationManager} (covers every nesting level and
 * logs each ignored field), not by a per-class annotation.
 */
public final class PigAgentConfig {

    @JsonProperty("workspace") private String workspacePath;
    @JsonProperty("model") private ModelConfig model = new ModelConfig();
    @JsonProperty("agent") private AgentConfig agent = new AgentConfig();
    @JsonProperty("channels") private Map<String, ChannelConfig> channels = Map.of();
    @JsonProperty("mcp") private McpConfig mcp = new McpConfig();
    @JsonProperty("compression") private CompressionConfig compression = new CompressionConfig();
    @JsonProperty("loop-detection") private LoopDetectionConfig loopDetection = new LoopDetectionConfig();
    @JsonProperty("permissions") private PermissionConfig permissions = new PermissionConfig();
    @JsonProperty("sandbox") private SandboxConfig sandbox = new SandboxConfig();
    @JsonProperty("tools") private ToolsConfig tools = new ToolsConfig();
    @JsonProperty("web") private WebConfig web = new WebConfig();
    @JsonProperty("memory") private MemoryConfig memory = new MemoryConfig();
    @JsonProperty("current-session-id") private String currentSessionId;
    @JsonProperty("memory-enabled") private boolean memoryEnabled = true;

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String path) { this.workspacePath = path; }
    public ModelConfig getModel() { return model; }
    public AgentConfig getAgent() { return agent; }
    public Map<String, ChannelConfig> getChannels() { return channels; }
    public McpConfig getMcp() { return mcp; }
    public CompressionConfig getCompression() { return compression; }
    public LoopDetectionConfig getLoopDetection() { return loopDetection; }
    public PermissionConfig getPermissions() { return permissions; }
    public SandboxConfig getSandbox() { return sandbox; }
    public ToolsConfig getTools() { return tools; }
    public WebConfig getWeb() { return web; }
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig m) { this.memory = m == null ? new MemoryConfig() : m; }
    public String getCurrentSessionId() { return currentSessionId; }
    public void setCurrentSessionId(String id) { this.currentSessionId = id; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(boolean enabled) { this.memoryEnabled = enabled; }

    public static final class ModelConfig {
        @JsonProperty("provider") private String provider = "anthropic";
        @JsonProperty("model-name") private String modelName = "claude-sonnet-4-6";
        @JsonProperty("retry") private RetryConfig retry = new RetryConfig();
        public String getProvider() { return provider; }
        public void setProvider(String p) { this.provider = p; }
        public String getModelName() { return modelName; }
        public void setModelName(String n) { this.modelName = n; }
        public RetryConfig getRetry() { return retry; }
        public void setRetry(RetryConfig r) { this.retry = r; }
    }

    /**
     * 模型调用重试（瞬时错误自愈）。缺省启用，向后兼容——无配置即用默认值；
     * {@code enabled: false} 完全旁路重试，等同旧行为。
     */
    public static final class RetryConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        @JsonProperty("max-retries") private int maxRetries = 10;
        // 0 = disabled (default). A client-side per-attempt timeout is unsafe with the current
        // non-interruptible ReActAgent (it would falsely trip on slow-but-healthy models and
        // re-subscribe into a still-running agent). Kept as an opt-in knob pending the
        // interruptible-run spike; leave at 0 unless you know what you're doing.
        @JsonProperty("per-attempt-timeout-seconds") private int perAttemptTimeoutSeconds = 0;
        @JsonProperty("first-backoff-ms") private long firstBackoffMs = 500;
        @JsonProperty("max-backoff-ms") private long maxBackoffMs = 8000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int m) { this.maxRetries = m; }
        public int getPerAttemptTimeoutSeconds() { return perAttemptTimeoutSeconds; }
        public void setPerAttemptTimeoutSeconds(int s) { this.perAttemptTimeoutSeconds = s; }
        public long getFirstBackoffMs() { return firstBackoffMs; }
        public void setFirstBackoffMs(long ms) { this.firstBackoffMs = ms; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long ms) { this.maxBackoffMs = ms; }
    }

    public static final class AgentConfig {
        @JsonProperty("name") private String name = "PigAgent";
        // Interactive/channel default: 40 iterations is generous enough for complex coding turns
        // without truncating them; autonomous agents stay conservative at AgentSpec.DEFAULT_MAX_ITERS (10).
        @JsonProperty("max-iters") private int maxIters = 40;
        public String getName() { return name; }
        public void setName(String n) { this.name = n; }
        public int getMaxIters() { return maxIters; }
        public void setMaxIters(int i) { this.maxIters = i; }
    }

    /**
     * 单个渠道的配置（键 {@code channels.<id>}）。缺省 {@code enabled=false}——不配置即不启动任何渠道。
     * {@code token} 用作通用鉴权/机器人令牌；{@code port}/{@code path} 供 HTTP 类渠道（webhook/slack/
     * dingtalk/feishu）监听用（{@code port<=0} 或 {@code path} 为空时渠道取内置默认）；{@code signing-secret}
     * 供 Slack 签名校验用；{@code webhook-url} 为出站机器人 webhook（dingtalk/feishu）；{@code sign-secret}
     * 为 dingtalk/feishu 出站+入站签名密钥；{@code verification-token} 为 feishu 事件订阅入站验签 token。
     * 全部新字段可选且有安全缺省（缺省 null）——旧配置（仅 {@code enabled}/{@code token}）照常解析。
     * 凭据字段（{@code sign-secret}/{@code verification-token}/{@code token}/{@code signing-secret}）不入日志/回显。
     */
    public static final class ChannelConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("token") private String token;
        @JsonProperty("port") private int port = 0;
        @JsonProperty("path") private String path;
        @JsonProperty("signing-secret") private String signingSecret;
        @JsonProperty("webhook-url") private String webhookUrl;
        @JsonProperty("sign-secret") private String signSecret;
        @JsonProperty("verification-token") private String verificationToken;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getToken() { return token; }
        public void setToken(String t) { this.token = t; }
        public int getPort() { return port; }
        public void setPort(int p) { this.port = p; }
        public String getPath() { return path; }
        public void setPath(String p) { this.path = p; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String s) { this.signingSecret = s; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String u) { this.webhookUrl = u; }
        public String getSignSecret() { return signSecret; }
        public void setSignSecret(String s) { this.signSecret = s; }
        public String getVerificationToken() { return verificationToken; }
        public void setVerificationToken(String t) { this.verificationToken = t; }
    }

    /**
     * 上下文压缩配置。基础触发（enabled / max-context-tokens / threshold）之上，新增「上下文工程」
     * 分层参数（context-engineering）：三层预算比例、递归摘要开关 + 限深、重要度/逐字/一致性开关、
     * 最近保留条数。全部可选、缺省安全——缺省值复现引入本能力前的「摘要旧回合 + 保留最近」行为。
     */
    public static final class CompressionConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        @JsonProperty("max-context-tokens") private int maxContextTokens = 32000;
        @JsonProperty("threshold") private double threshold = 0.8;
        @JsonProperty("keep-recent") private int keepRecent = 6;
        @JsonProperty("recursive-summary") private boolean recursiveSummary = true;
        @JsonProperty("max-summary-depth") private int maxSummaryDepth = 3;
        @JsonProperty("importance-retention") private boolean importanceRetention = true;
        @JsonProperty("verbatim-protection") private boolean verbatimProtection = true;
        @JsonProperty("consistency-check") private boolean consistencyCheck = true;
        @JsonProperty("pinned-ratio") private double pinnedRatio = 0.2;
        @JsonProperty("recent-ratio") private double recentRatio = 0.3;
        @JsonProperty("summarized-ratio") private double summarizedRatio = 0.5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int t) { this.maxContextTokens = t; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double t) { this.threshold = t; }
        public int getKeepRecent() { return keepRecent; }
        public void setKeepRecent(int k) { this.keepRecent = k; }
        public boolean isRecursiveSummary() { return recursiveSummary; }
        public void setRecursiveSummary(boolean r) { this.recursiveSummary = r; }
        public int getMaxSummaryDepth() { return maxSummaryDepth; }
        public void setMaxSummaryDepth(int d) { this.maxSummaryDepth = d; }
        public boolean isImportanceRetention() { return importanceRetention; }
        public void setImportanceRetention(boolean i) { this.importanceRetention = i; }
        public boolean isVerbatimProtection() { return verbatimProtection; }
        public void setVerbatimProtection(boolean v) { this.verbatimProtection = v; }
        public boolean isConsistencyCheck() { return consistencyCheck; }
        public void setConsistencyCheck(boolean c) { this.consistencyCheck = c; }
        public double getPinnedRatio() { return pinnedRatio; }
        public void setPinnedRatio(double r) { this.pinnedRatio = r; }
        public double getRecentRatio() { return recentRatio; }
        public void setRecentRatio(double r) { this.recentRatio = r; }
        public double getSummarizedRatio() { return summarizedRatio; }
        public void setSummarizedRatio(double r) { this.summarizedRatio = r; }
    }

    /**
     * 记忆配置。当前仅含 {@code extraction}（记忆 LLM 抽取）子块，全部可选、默认安全。
     * 注意：全局记忆读写总开关是顶层的 {@code memory-enabled}（历史字段，保持不动）。
     */
    public static final class MemoryConfig {
        @JsonProperty("extraction") private MemoryExtractionConfig extraction = new MemoryExtractionConfig();
        public MemoryExtractionConfig getExtraction() { return extraction; }
        public void setExtraction(MemoryExtractionConfig e) {
            this.extraction = e == null ? new MemoryExtractionConfig() : e;
        }
    }

    /**
     * 记忆 LLM 抽取（memory-extraction）。默认 {@code enabled=false} → 逐字节保留原始「死记原始回合」的
     * record 行为（不起抽取模型调用、不起后台线程），需显式开启。启用后：会话层 record 改为用当前模型
     * 从完成的回合中抽取带类别 + 置信度的结构化事实，经置信度门（{@code confidence-threshold}，默认 0.7）
     * + 纠正覆盖 + 噪声过滤后，异步去抖（{@code debounce-ms}，默认 2000）写入。全部可选、向后兼容。
     */
    public static final class MemoryExtractionConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("confidence-threshold") private double confidenceThreshold = 0.7;
        @JsonProperty("debounce-ms") private long debounceMs = 2000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public double getConfidenceThreshold() { return confidenceThreshold; }
        public void setConfidenceThreshold(double t) { this.confidenceThreshold = t; }
        public long getDebounceMs() { return debounceMs; }
        public void setDebounceMs(long ms) { this.debounceMs = ms; }
    }

    /**
     * 工具调用循环检测（loop-detection）。缺省启用、阈值宽松、向后兼容——无配置即用默认值
     * （窗口 20、warn 3、stop 5）；{@code enabled: false} 完全旁路，等同引入本能力之前的行为。
     * 越界值由 {@code LoopDetector} 容错钳制（窗口/warn ≥ 1、stop ≥ warn），不会因坏配置崩溃。
     */
    public static final class LoopDetectionConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        @JsonProperty("window-size") private int windowSize = 20;
        @JsonProperty("warn-threshold") private int warnThreshold = 3;
        @JsonProperty("stop-threshold") private int stopThreshold = 5;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int s) { this.windowSize = s; }
        public int getWarnThreshold() { return warnThreshold; }
        public void setWarnThreshold(int t) { this.warnThreshold = t; }
        public int getStopThreshold() { return stopThreshold; }
        public void setStopThreshold(int t) { this.stopThreshold = t; }
    }

    /** 工具权限体系配置（全局）。缺省 mode=ask、channel-mode=auto，向后兼容。 */
    public static final class PermissionConfig {
        @JsonProperty("mode") private String mode = "ask";
        @JsonProperty("channel-mode") private String channelMode = "auto";
        @JsonProperty("tool-overrides") private Map<String, String> toolOverrides = new java.util.LinkedHashMap<>();
        @JsonProperty("allowlist") private Allowlist allowlist = new Allowlist();

        public String getMode() { return mode; }
        public void setMode(String m) { this.mode = m; }
        public String getChannelMode() { return channelMode; }
        public void setChannelMode(String m) { this.channelMode = m; }
        public Map<String, String> getToolOverrides() { return toolOverrides; }
        public void setToolOverrides(Map<String, String> m) { this.toolOverrides = m; }
        public Allowlist getAllowlist() { return allowlist; }
        public void setAllowlist(Allowlist a) { this.allowlist = a; }

        /** 解析交互模式，未知值回退 ASK。 */
        public PermissionMode resolveMode() { return PermissionMode.fromString(mode, PermissionMode.ASK); }
        /** 解析非交互渠道模式，未知值回退 AUTO。 */
        public PermissionMode resolveChannelMode() { return PermissionMode.fromString(channelMode, PermissionMode.AUTO); }

        /** 持久化的"始终允许"清单：工具名 + 规范化命令键。 */
        public static final class Allowlist {
            @JsonProperty("tools") private java.util.List<String> tools = new java.util.ArrayList<>();
            @JsonProperty("commands") private java.util.List<String> commands = new java.util.ArrayList<>();
            public java.util.List<String> getTools() { return tools; }
            public void setTools(java.util.List<String> t) { this.tools = t; }
            public java.util.List<String> getCommands() { return commands; }
            public void setCommands(java.util.List<String> c) { this.commands = c; }
        }
    }

    /**
     * 命令执行沙箱配置（exec-sandbox）。全部可选、默认安全、向后兼容——缺 {@code sandbox} 块即等价于
     * 「保守内置 denylist + 200KB 输出上限 + 30s 超时 + env 脱敏开 + 不限 cwd」。与 {@code permissions}
     * （may-run）正交：权限决定能否运行，沙箱决定运行时被约束到什么程度。
     */
    public static final class SandboxConfig {
        @JsonProperty("exec") private ExecSandboxConfig exec = new ExecSandboxConfig();
        public ExecSandboxConfig getExec() { return exec; }
        public void setExec(ExecSandboxConfig e) { this.exec = e; }
    }

    /**
     * {@code executeCommand} 的受约束执行配置。{@code max-output-bytes}/{@code timeout-seconds} 非法
     * （{@code <=0}）时由 {@code SandboxPolicy} 容错钳制回默认；{@code denylist} 只在内置灾难性模式
     * 底线之上**追加**用户正则（不能削弱底线）；{@code warnlist} 只在内置中危 warn 集之上**追加**用户
     * 正则（命中→照常执行但结果追加 ⚠️ 提示，不改 block/pass 契约；不能削弱内置集）；{@code scrub-env}
     * 默认剔除凭据类环境变量；{@code working-dir} 为空则继承当前工作目录。
     */
    public static final class ExecSandboxConfig {
        @JsonProperty("max-output-bytes") private long maxOutputBytes = 200_000;
        @JsonProperty("timeout-seconds") private int timeoutSeconds = 30;
        @JsonProperty("denylist") private java.util.List<String> denylist = new java.util.ArrayList<>();
        @JsonProperty("warnlist") private java.util.List<String> warnlist = new java.util.ArrayList<>();
        @JsonProperty("scrub-env") private boolean scrubEnv = true;
        @JsonProperty("working-dir") private String workingDir;
        public long getMaxOutputBytes() { return maxOutputBytes; }
        public void setMaxOutputBytes(long b) { this.maxOutputBytes = b; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int s) { this.timeoutSeconds = s; }
        public java.util.List<String> getDenylist() { return denylist; }
        public void setDenylist(java.util.List<String> d) {
            this.denylist = d == null ? new java.util.ArrayList<>() : d;
        }
        public java.util.List<String> getWarnlist() { return warnlist; }
        public void setWarnlist(java.util.List<String> w) {
            this.warnlist = w == null ? new java.util.ArrayList<>() : w;
        }
        public boolean isScrubEnv() { return scrubEnv; }
        public void setScrubEnv(boolean e) { this.scrubEnv = e; }
        public String getWorkingDir() { return workingDir; }
        public void setWorkingDir(String w) { this.workingDir = w; }
    }

    /** 内置工具配置。缺省全空，向后兼容。 */
    public static final class ToolsConfig {
        @JsonProperty("web") private WebToolConfig web = new WebToolConfig();
        @JsonProperty("deferred") private DeferredToolsConfig deferred = new DeferredToolsConfig();
        @JsonProperty("result-eviction") private ResultEvictionConfig resultEviction = new ResultEvictionConfig();
        public WebToolConfig getWeb() { return web; }
        public void setWeb(WebToolConfig w) { this.web = w; }
        public DeferredToolsConfig getDeferred() { return deferred; }
        public void setDeferred(DeferredToolsConfig d) {
            this.deferred = d == null ? new DeferredToolsConfig() : d;
        }
        public ResultEvictionConfig getResultEviction() { return resultEviction; }
        public void setResultEviction(ResultEvictionConfig r) {
            this.resultEviction = r == null ? new ResultEvictionConfig() : r;
        }
    }

    /**
     * 工具结果驱逐（{@code tools.result-eviction}，av2 Phase 5b）配置——pig 原本缺失、由 HarnessAgent 原生
     * 提供的能力：单条工具结果超过 {@code threshold} 字符时，完整内容落盘到工作区 {@code dir} 目录、上下文里
     * 只留一个「已保存到 …，用 read_file 读取」占位符（含前若干字符预览），防止大文件读取 / 命令输出把上下文撑爆。
     * 默认 {@code enabled=true}、{@code threshold=80000}（约 80K 字符，对齐原生
     * {@code ToolResultEvictionConfig.DEFAULT_MAX_RESULT_CHARS}）。{@code enabled=false} 完全关闭。全部
     * 可选、默认安全、向后兼容（旧配置无此块 → 默认开、按 80K 门限）。
     */
    public static final class ResultEvictionConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        @JsonProperty("threshold") private int threshold = 80000;
        @JsonProperty("preview-chars") private int previewChars = 2000;
        @JsonProperty("dir") private String dir = "/large_tool_results";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public int getThreshold() { return threshold; }
        public void setThreshold(int t) { this.threshold = t; }
        public int getPreviewChars() { return previewChars; }
        public void setPreviewChars(int p) { this.previewChars = p; }
        public String getDir() { return dir; }
        public void setDir(String d) { this.dir = d; }
    }

    /**
     * 延迟工具（{@code deferred-tools}）配置。默认 {@code enabled=false} → 一切照旧（不隐藏任何工具、
     * 不注册 {@code tool_search}、不对 MCP 工具分组）。启用后：{@code tools} 显式清单 + 阈值规则
     * （{@code auto-defer-mcp} 且总工具数超 {@code threshold} 时自动延迟全部 MCP 工具）决定隐藏哪些工具，
     * 模型经 {@code tool_search} 按需发现并揭示，省提示词 token。
     */
    public static final class DeferredToolsConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("tools") private java.util.List<String> tools = java.util.List.of();
        @JsonProperty("auto-defer-mcp") private boolean autoDeferMcp = true;
        @JsonProperty("threshold") private int threshold = 25;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public java.util.List<String> getTools() { return tools; }
        public void setTools(java.util.List<String> t) {
            this.tools = t == null ? java.util.List.of() : t;
        }
        public boolean isAutoDeferMcp() { return autoDeferMcp; }
        public void setAutoDeferMcp(boolean a) { this.autoDeferMcp = a; }
        public int getThreshold() { return threshold; }
        public void setThreshold(int t) { this.threshold = t; }
    }

    /**
     * web-fetch 工具配置。{@code allowed-hosts} 为可选主机白名单：非空时 {@code fetchUrl} 仅放行
     * 白名单内主机（叠加在 SSRF IP 守卫之上）；为空则仅施加 SSRF 守卫。缺省空。
     */
    public static final class WebToolConfig {
        @JsonProperty("allowed-hosts") private java.util.List<String> allowedHosts = java.util.List.of();
        public java.util.List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(java.util.List<String> h) {
            this.allowedHosts = h == null ? java.util.List.of() : h;
        }
    }

    /**
     * 本地 Web 控制台（{@code web-console}）。默认关闭；启用时嵌入式 HTTP server 随
     * CLI 进程启停，MUST 仅绑本机（{@code host} 默认 127.0.0.1）——个人电脑、单用户、无 DB 的安全底线。
     */
    public static final class WebConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("host") private String host = "127.0.0.1";
        @JsonProperty("port") private int port = 7317;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getHost() { return host; }
        public void setHost(String h) { this.host = h; }
        public int getPort() { return port; }
        public void setPort(int p) { this.port = p; }
    }

    public static final class McpConfig {
        @JsonProperty("servers") private Map<String, McpServerConfig> servers = Map.of();
        @JsonProperty("agent-management") private AgentManagementConfig agentManagement = new AgentManagementConfig();
        public Map<String, McpServerConfig> getServers() { return servers; }
        public AgentManagementConfig getAgentManagement() { return agentManagement; }
    }

    /** agent 经 McpTool 自助管理 MCP 的安全门（D-SEC）。默认全关。 */
    public static final class AgentManagementConfig {
        @JsonProperty("allow-add") private boolean allowAdd = false;
        @JsonProperty("allow-remove") private boolean allowRemove = false;
        @JsonProperty("allowed-hosts") private java.util.List<String> allowedHosts = java.util.List.of();
        public boolean isAllowAdd() { return allowAdd; }
        public void setAllowAdd(boolean v) { this.allowAdd = v; }
        public boolean isAllowRemove() { return allowRemove; }
        public void setAllowRemove(boolean v) { this.allowRemove = v; }
        public java.util.List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(java.util.List<String> h) { this.allowedHosts = h; }
    }

    public static final class McpServerConfig {
        @JsonProperty("command") private String command;
        @JsonProperty("args") private java.util.List<String> args = java.util.List.of();
        @JsonProperty("env") private Map<String, String> env = Map.of();
        @JsonProperty("url") private String url;
        @JsonProperty("streamable-http") private boolean streamableHttp = false;
        @JsonProperty("headers") private Map<String, String> headers = Map.of();
        public String getCommand() { return command; }
        public java.util.List<String> getArgs() { return args; }
        public Map<String, String> getEnv() { return env; }
        public String getUrl() { return url; }
        public boolean isStreamableHttp() { return streamableHttp; }
        public Map<String, String> getHeaders() { return headers; }
    }
}
