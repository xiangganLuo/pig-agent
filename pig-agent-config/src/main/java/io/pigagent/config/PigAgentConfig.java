package io.pigagent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Root application config. Unknown/unrecognized fields are ignored rather than failing the whole
 * load and reverting to all-defaults — so config schema drift (a new-version field read by an old
 * build, or a stale field left in the file) never silently discards the user's recognized settings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PigAgentConfig {

    @JsonProperty("workspace") private String workspacePath;
    @JsonProperty("model") private ModelConfig model = new ModelConfig();
    @JsonProperty("agent") private AgentConfig agent = new AgentConfig();
    @JsonProperty("channels") private Map<String, ChannelConfig> channels = Map.of();
    @JsonProperty("mcp") private McpConfig mcp = new McpConfig();
    @JsonProperty("compression") private CompressionConfig compression = new CompressionConfig();
    @JsonProperty("permissions") private PermissionConfig permissions = new PermissionConfig();
    @JsonProperty("tools") private ToolsConfig tools = new ToolsConfig();
    @JsonProperty("web") private WebConfig web = new WebConfig();
    @JsonProperty("current-session-id") private String currentSessionId;
    @JsonProperty("memory-enabled") private boolean memoryEnabled = true;

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String path) { this.workspacePath = path; }
    public ModelConfig getModel() { return model; }
    public AgentConfig getAgent() { return agent; }
    public Map<String, ChannelConfig> getChannels() { return channels; }
    public McpConfig getMcp() { return mcp; }
    public CompressionConfig getCompression() { return compression; }
    public PermissionConfig getPermissions() { return permissions; }
    public ToolsConfig getTools() { return tools; }
    public WebConfig getWeb() { return web; }
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
     * {@code token} 用作通用鉴权/机器人令牌；{@code port}/{@code path} 供 HTTP 类渠道（webhook/slack）
     * 监听用（{@code port<=0} 或 {@code path} 为空时渠道取内置默认）；{@code signing-secret} 供 Slack
     * 签名校验用。全部新字段可选且有安全缺省——旧配置（仅 {@code enabled}/{@code token}）照常解析。
     */
    public static final class ChannelConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("token") private String token;
        @JsonProperty("port") private int port = 0;
        @JsonProperty("path") private String path;
        @JsonProperty("signing-secret") private String signingSecret;
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
    }

    public static final class CompressionConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        @JsonProperty("max-context-tokens") private int maxContextTokens = 32000;
        @JsonProperty("threshold") private double threshold = 0.8;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int t) { this.maxContextTokens = t; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double t) { this.threshold = t; }
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

    /** 内置工具配置。缺省全空，向后兼容。 */
    public static final class ToolsConfig {
        @JsonProperty("web") private WebToolConfig web = new WebToolConfig();
        public WebToolConfig getWeb() { return web; }
        public void setWeb(WebToolConfig w) { this.web = w; }
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
