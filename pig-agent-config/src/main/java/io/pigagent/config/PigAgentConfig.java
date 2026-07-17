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
    @JsonProperty("channel-gateway") private ChannelGatewayConfig channelGateway = new ChannelGatewayConfig();
    @JsonProperty("mcp") private McpConfig mcp = new McpConfig();
    @JsonProperty("compression") private CompressionConfig compression = new CompressionConfig();
    @JsonProperty("loop-detection") private LoopDetectionConfig loopDetection = new LoopDetectionConfig();
    @JsonProperty("permissions") private PermissionConfig permissions = new PermissionConfig();
    @JsonProperty("sandbox") private SandboxConfig sandbox = new SandboxConfig();
    @JsonProperty("tools") private ToolsConfig tools = new ToolsConfig();
    @JsonProperty("subagents") private SubagentsConfig subagents = new SubagentsConfig();
    @JsonProperty("plan-mode") private PlanModeConfig planMode = new PlanModeConfig();
    @JsonProperty("web") private WebConfig web = new WebConfig();
    @JsonProperty("memory") private MemoryConfig memory = new MemoryConfig();
    @JsonProperty("skills") private SkillsConfig skills = new SkillsConfig();
    @JsonProperty("user-profile") private UserProfileConfig userProfile = new UserProfileConfig();
    @JsonProperty("outreach") private OutreachConfig outreach = new OutreachConfig();
    @JsonProperty("current-session-id") private String currentSessionId;
    @JsonProperty("memory-enabled") private boolean memoryEnabled = true;

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String path) { this.workspacePath = path; }
    public ModelConfig getModel() { return model; }
    public AgentConfig getAgent() { return agent; }
    public Map<String, ChannelConfig> getChannels() { return channels; }
    public ChannelGatewayConfig getChannelGateway() { return channelGateway; }
    public McpConfig getMcp() { return mcp; }
    public CompressionConfig getCompression() { return compression; }
    public LoopDetectionConfig getLoopDetection() { return loopDetection; }
    public PermissionConfig getPermissions() { return permissions; }
    public SandboxConfig getSandbox() { return sandbox; }
    public ToolsConfig getTools() { return tools; }
    public SubagentsConfig getSubagents() { return subagents; }
    public void setSubagents(SubagentsConfig s) { this.subagents = s == null ? new SubagentsConfig() : s; }
    public PlanModeConfig getPlanMode() { return planMode; }
    public void setPlanMode(PlanModeConfig p) { this.planMode = p == null ? new PlanModeConfig() : p; }
    public WebConfig getWeb() { return web; }
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig m) { this.memory = m == null ? new MemoryConfig() : m; }
    public SkillsConfig getSkills() { return skills; }
    public void setSkills(SkillsConfig s) { this.skills = s == null ? new SkillsConfig() : s; }
    public UserProfileConfig getUserProfile() { return userProfile; }
    public void setUserProfile(UserProfileConfig u) { this.userProfile = u == null ? new UserProfileConfig() : u; }
    public OutreachConfig getOutreach() { return outreach; }
    public void setOutreach(OutreachConfig o) { this.outreach = o == null ? new OutreachConfig() : o; }
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
        // av2 Gateway enhancement: opt this channel into the native AgentScope 2.0 channel adapter
        // (DingTalk/Feishu/GitHub/GitLab/WeCom) instead of pig's custom adapter. Requires
        // `channel-gateway.enabled` AND the matching `agentscope-extensions-channel-*` artifact on the
        // classpath; when the artifact is absent it degrades gracefully to pig's custom channel. The
        // adapter's platform credentials/settings live under `props` (e.g. appKey/appSecret/robotCode).
        @JsonProperty("native") private boolean nativeAdapter = false;
        @JsonProperty("props") private Map<String, String> props = Map.of();
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
        public boolean isNative() { return nativeAdapter; }
        public void setNative(boolean n) { this.nativeAdapter = n; }
        public Map<String, String> getProps() { return props == null ? Map.of() : props; }
        public void setProps(Map<String, String> p) { this.props = p; }
    }

    /**
     * av2 Gateway enhancement — gate for adopting the native AgentScope 2.0 {@code Gateway}/
     * {@code ChatUiChannel} channel kernel (session management + single-session concurrency + agent
     * routing) behind pig's channel layer, plus the native platform adapters. <b>Default off</b> — when
     * disabled the channel layer behaves exactly as before (pig's custom channels route turns directly
     * through the channel agent), so this is fully backward compatible. When enabled, channels route
     * through the native gateway and channels flagged {@code native:true} use the native adapter.
     */
    public static final class ChannelGatewayConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("main-agent-id") private String mainAgentId = "default";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getMainAgentId() { return mainAgentId; }
        public void setMainAgentId(String id) { this.mainAgentId = id; }
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
     * 记忆配置（{@code pa-memory-native}）——映射 AgentScope 2.0 原生两层记忆 {@code MemoryConfig}
     * （flush 每回合抽事实入日志层 {@code memory/YYYY-MM-DD.md}，consolidation 后台去重合并到工作区级
     * {@code MEMORY.md}）。全部可选、默认安全。
     *
     * <p>注意：记忆读写总开关是顶层的 {@code memory-enabled}（历史字段，默认 true，保持不动）——{@code /memory
     * on|off} 走它并触发重建；本块只承载 flush/consolidation 的细节 + 廉价辅助模型 id。
     * <ul>
     *   <li>{@code flush}：{@code always}（默认，每回合）| {@code never} | {@code throttled}（配
     *       {@code flush-throttle-minutes}）。</li>
     *   <li>{@code flush-throttle-minutes}：{@code throttled} 时的最小间隔（分钟，默认 0=退化为 always）。</li>
     *   <li>{@code consolidation-min-gap-minutes}：后台合并最小间隔（默认 30）——使固化层在一次会话内通常稳定。</li>
     *   <li>{@code consolidation-max-tokens}：固化层重写 token 上限（默认 4000）。</li>
     *   <li>{@code model-id}：flush/consolidation 用的廉价模型 id（如 Doubao lite）；空 = 回退主推理模型。</li>
     * </ul>
     */
    public static final class MemoryConfig {
        @JsonProperty("flush") private String flush = "always";
        @JsonProperty("flush-throttle-minutes") private int flushThrottleMinutes = 0;
        @JsonProperty("consolidation-min-gap-minutes") private int consolidationMinGapMinutes = 30;
        @JsonProperty("consolidation-max-tokens") private int consolidationMaxTokens = 4000;
        @JsonProperty("model-id") private String modelId = "";
        @JsonProperty("search") private SearchConfig search = new SearchConfig();

        public String getFlush() { return flush; }
        public void setFlush(String f) { this.flush = f == null || f.isBlank() ? "always" : f; }
        public int getFlushThrottleMinutes() { return flushThrottleMinutes; }
        public void setFlushThrottleMinutes(int m) { this.flushThrottleMinutes = m; }
        public int getConsolidationMinGapMinutes() { return consolidationMinGapMinutes; }
        public void setConsolidationMinGapMinutes(int m) { this.consolidationMinGapMinutes = m; }
        public int getConsolidationMaxTokens() { return consolidationMaxTokens; }
        public void setConsolidationMaxTokens(int t) { this.consolidationMaxTokens = t; }
        public String getModelId() { return modelId; }
        public void setModelId(String id) { this.modelId = id == null ? "" : id; }
        public SearchConfig getSearch() { return search; }
        public void setSearch(SearchConfig s) { this.search = s == null ? new SearchConfig() : s; }
    }

    /**
     * 混合记忆检索（{@code memory.search}，能力 {@code hybrid-memory-search}）——在记忆库
     * （{@code MEMORY.md} + {@code memory/*.md} + {@code USER.md}）上叠加 BM25+向量混合检索
     * （OpenClaw 蓝本）。<b>默认 {@code hybrid-enabled=false}</b>：关闭时不注册 pig {@code memory_search}、
     * 保留 2.0 原生纯关键词检索——逐字节等于本能力引入前（保守：真实嵌入器未经 live 验证前不改默认检索行为）。
     * <ul>
     *   <li>{@code bm25-weight}/{@code vector-weight}：混合权重（各成分归一化后加权，默认 {@code 0.7}/{@code 0.3}）。</li>
     *   <li>{@code embedder-model-id}：向量嵌入用的 OpenAI-compatible 模型 id（空 → 无嵌入器 → BM25-only）。</li>
     *   <li>{@code candidate-multiplier}：混合前取 {@code top-k × 该值} 个向量候选（默认 4）。</li>
     *   <li>{@code min-score}：融合分低于此阈值的命中丢弃（默认 0.0=不过滤）。</li>
     *   <li>{@code top-k}：返回命中上限（默认 8）。</li>
     *   <li>{@code rebuild-throttle-seconds}：增量重建最小间隔秒（默认 5）。</li>
     * </ul>
     * 全部可选、默认安全。
     */
    public static final class SearchConfig {
        @JsonProperty("hybrid-enabled") private boolean hybridEnabled = false;
        @JsonProperty("bm25-weight") private double bm25Weight = 0.7;
        @JsonProperty("vector-weight") private double vectorWeight = 0.3;
        @JsonProperty("embedder-model-id") private String embedderModelId = "";
        @JsonProperty("candidate-multiplier") private int candidateMultiplier = 4;
        @JsonProperty("min-score") private double minScore = 0.0;
        @JsonProperty("top-k") private int topK = 8;
        @JsonProperty("rebuild-throttle-seconds") private int rebuildThrottleSeconds = 5;

        public boolean isHybridEnabled() { return hybridEnabled; }
        public void setHybridEnabled(boolean e) { this.hybridEnabled = e; }
        public double getBm25Weight() { return bm25Weight; }
        public void setBm25Weight(double w) { this.bm25Weight = w; }
        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double w) { this.vectorWeight = w; }
        public String getEmbedderModelId() { return embedderModelId; }
        public void setEmbedderModelId(String id) { this.embedderModelId = id == null ? "" : id; }
        public int getCandidateMultiplier() { return candidateMultiplier; }
        public void setCandidateMultiplier(int m) { this.candidateMultiplier = m; }
        public double getMinScore() { return minScore; }
        public void setMinScore(double s) { this.minScore = s; }
        public int getTopK() { return topK; }
        public void setTopK(int k) { this.topK = k; }
        public int getRebuildThrottleSeconds() { return rebuildThrottleSeconds; }
        public void setRebuildThrottleSeconds(int s) { this.rebuildThrottleSeconds = s; }
    }

    /**
     * 自主沉淀 skills（autonomous-skills）。默认 {@code enabled=false} → 不注册 {@code proposeSkill}/
     * {@code skillManage} 写工具（经 availability 门从模型 schema 隐藏），行为逐字节等价引入本能力前
     * （只读技能栈不变、无写路径）。启用后：agent 可把「解过的非平凡任务/工作流」蒸馏成 {@code SKILL.md}
     * 草稿写入暂存区 {@code workspace/skills/<staging-dir>/<name>/}（{@code staging-dir} 默认 {@code .pending}），
     * <b>默认人工门</b>（{@code /skill review|approve|reject}）审批后经安全扫描 + 去重原子提升到
     * {@code workspace/skills/<name>/}。{@code auto-promote} 默认 false = 纯人工门；为 true 时交互 REPL 一轮结束后
     * 对暂存草稿自动跑扫描 + 去重 + 提升（渠道/自主轨道无此 hook → 永不自动提升，fail-closed）。全部可选、
     * 默认安全、向后兼容。
     */
    public static final class SkillsConfig {
        @JsonProperty("autonomous") private AutonomousSkillsConfig autonomous = new AutonomousSkillsConfig();
        public AutonomousSkillsConfig getAutonomous() { return autonomous; }
        public void setAutonomous(AutonomousSkillsConfig a) {
            this.autonomous = a == null ? new AutonomousSkillsConfig() : a;
        }
    }

    /** {@code skills.autonomous} 子块。缺省全安全（关闭 + {@code .pending} 暂存 + 不自动提升）。 */
    public static final class AutonomousSkillsConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("staging-dir") private String stagingDir = ".pending";
        @JsonProperty("auto-promote") private boolean autoPromote = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getStagingDir() { return stagingDir; }
        public void setStagingDir(String d) {
            this.stagingDir = (d == null || d.isBlank()) ? ".pending" : d;
        }
        public boolean isAutoPromote() { return autoPromote; }
        public void setAutoPromote(boolean a) { this.autoPromote = a; }
    }

    /**
     * 用户画像配置（{@code user-profile}，Hermes {@code USER.md} 蓝本）——一份**专门的、策展的**用户画像
     * （身份/长期偏好/工作方式），区别于 {@code pa-memory-native} 的通用事实日志 {@code MEMORY.md}。启用时
     * 画像文件 {@code USER.md}（工作区级，路径 {@code path}）被有界（{@code max-chars}）、凭据脱敏地注入
     * system prompt（在 {@code MEMORY.md} 之前），并暴露 {@code updateProfile} 写工具（WRITE，确定性 set/merge）。
     *
     * <p><b>默认 {@code enabled=true}</b>——但禁用即今日行为：不注入 {@code USER.md}、不暴露 {@code updateProfile}、
     * 不蒸馏。全部字段可选、默认安全、向后兼容（缺块 → 启用、默认路径/上限、蒸馏关）。
     * <ul>
     *   <li>{@code path}：画像文件的工作区相对路径（默认 {@code USER.md}）。</li>
     *   <li>{@code max-chars}：注入 system prompt 的画像上限（超出截断；默认 4000）。</li>
     *   <li>{@code consolidation}：可选的后台画像蒸馏（廉价模型把稳定偏好蒸馏进去重后的 {@code USER.md}），
     *       <b>默认关</b>（真实蒸馏质量属 live-model 验证）。</li>
     * </ul>
     */
    public static final class UserProfileConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        @JsonProperty("path") private String path = "USER.md";
        @JsonProperty("max-chars") private int maxChars = 4000;
        @JsonProperty("consolidation") private ProfileConsolidationConfig consolidation = new ProfileConsolidationConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getPath() { return path; }
        public void setPath(String p) { this.path = p == null || p.isBlank() ? "USER.md" : p; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int c) { this.maxChars = c; }
        public ProfileConsolidationConfig getConsolidation() { return consolidation; }
        public void setConsolidation(ProfileConsolidationConfig c) {
            this.consolidation = c == null ? new ProfileConsolidationConfig() : c;
        }
    }

    /**
     * 后台画像蒸馏配置（{@code user-profile.consolidation}）。<b>默认 {@code enabled=false}</b>（保守——真实
     * 蒸馏质量属 live-model，交集成测试）。开启时经 {@code TaskScheduler} 后台按 {@code min-gap-minutes}
     * 触发，用一个廉价模型（{@code model-id}，空 → 记忆廉价模型 → 主推理模型）把 {@code MEMORY.md} 里稳定的
     * 身份/偏好蒸馏进去重后的 {@code USER.md}；服务自身再节流、容错，不阻塞回合。
     */
    public static final class ProfileConsolidationConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("min-gap-minutes") private int minGapMinutes = 60;
        @JsonProperty("model-id") private String modelId = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public int getMinGapMinutes() { return minGapMinutes; }
        public void setMinGapMinutes(int m) { this.minGapMinutes = m; }
        public String getModelId() { return modelId; }
        public void setModelId(String id) { this.modelId = id == null ? "" : id; }
    }

    /**
     * 主动外呼 + 通知（proactive-outreach）。默认 {@code enabled=false} → 不外呼、{@code notifyUser}
     * 工具隐藏、无定时简报、无晨报推送——零行为变化。启用后：{@code channel}/{@code recipient} 为默认目标；
     * {@code quiet-hours}/{@code rate-limit}/{@code dedup-window-minutes} 为防打扰护栏（紧急绕行免打扰/限流，
     * 去重始终生效）；{@code briefing} 为定时简报（cron）；{@code report-push} 为数字员工晨报推渠道。
     * 全部可选、默认安全、向后兼容；接收人凭据不入日志/回显。
     */
    public static final class OutreachConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("channel") private String channel = "";
        @JsonProperty("recipient") private String recipient = "";
        @JsonProperty("quiet-hours") private QuietHoursConfig quietHours = new QuietHoursConfig();
        @JsonProperty("rate-limit") private RateLimitConfig rateLimit = new RateLimitConfig();
        @JsonProperty("dedup-window-minutes") private int dedupWindowMinutes = 30;
        @JsonProperty("briefing") private BriefingConfig briefing = new BriefingConfig();
        @JsonProperty("report-push") private ReportPushConfig reportPush = new ReportPushConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getChannel() { return channel; }
        public void setChannel(String c) { this.channel = c; }
        public String getRecipient() { return recipient; }
        public void setRecipient(String r) { this.recipient = r; }
        public QuietHoursConfig getQuietHours() { return quietHours; }
        public void setQuietHours(QuietHoursConfig q) { this.quietHours = q == null ? new QuietHoursConfig() : q; }
        public RateLimitConfig getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimitConfig r) { this.rateLimit = r == null ? new RateLimitConfig() : r; }
        public int getDedupWindowMinutes() { return dedupWindowMinutes; }
        public void setDedupWindowMinutes(int m) { this.dedupWindowMinutes = m; }
        public BriefingConfig getBriefing() { return briefing; }
        public void setBriefing(BriefingConfig b) { this.briefing = b == null ? new BriefingConfig() : b; }
        public ReportPushConfig getReportPush() { return reportPush; }
        public void setReportPush(ReportPushConfig r) { this.reportPush = r == null ? new ReportPushConfig() : r; }
    }

    /** 免打扰时段（{@code HH:mm} 24 小时制，可跨午夜）。缺省关闭。 */
    public static final class QuietHoursConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("start") private String start = "22:00";
        @JsonProperty("end") private String end = "08:00";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getStart() { return start; }
        public void setStart(String s) { this.start = s; }
        public String getEnd() { return end; }
        public void setEnd(String e) { this.end = e; }
    }

    /** 外呼限流：{@code window-minutes} 窗口内最多 {@code max-per-window} 条（≤0 不限）。 */
    public static final class RateLimitConfig {
        @JsonProperty("max-per-window") private int maxPerWindow = 5;
        @JsonProperty("window-minutes") private int windowMinutes = 60;
        public int getMaxPerWindow() { return maxPerWindow; }
        public void setMaxPerWindow(int m) { this.maxPerWindow = m; }
        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int m) { this.windowMinutes = m; }
    }

    /** 定时简报：到 {@code cron} 时推一条 {@code title}/{@code body} 通知。缺省关闭。 */
    public static final class BriefingConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("cron") private String cron = "0 9 * * *";
        @JsonProperty("title") private String title = "每日简报";
        @JsonProperty("body") private String body = "早上好，这是你的每日简报。";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getCron() { return cron; }
        public void setCron(String c) { this.cron = c; }
        public String getTitle() { return title; }
        public void setTitle(String t) { this.title = t; }
        public String getBody() { return body; }
        public void setBody(String b) { this.body = b; }
    }

    /** 晨报推送：数字员工晨报除写文件外，是否也推到默认渠道。缺省关闭。 */
    public static final class ReportPushConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
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

    /**
     * 原生子 agent 委派（{@code subagents}，av2 Phase 6a）配置——「编排」北极星能力：当前 agent 可把
     * 「独立、上下文重、可并行」的子任务委派给一个瞬态子 agent（内置 {@code general-purpose} + 工作区
     * {@code subagents/<id>.md} 声明），子 agent 跑完把结果回传父 agent。这与 pig 的 <b>peer</b> agent
     * （{@code /agent use} 切换当前 agent）是两个正交概念，互不冲突：peer=切换活跃 agent，subagent=活跃
     * agent 委派子任务给子 agent。
     *
     * <p>{@code enabled} <b>默认 true</b>（av2 Phase-6b 起）。此前默认 false，唯一阻塞理由是<b>权限逃逸</b>：
     * AgentScope 2.0.0 的 {@code SubagentDeclaration.inheritParentPermissions} 字段<em>已声明但未接线</em>
     * （harness 里没有任何类读它，javap 证实），被 spawn 的子 agent 会以自身宽松权限运行，不继承父 agent
     * 的 DENY 规则。Phase-6b <b>已在 pig 侧接线继承</b>：pig 通过自定义 {@code HarnessAgent.Builder
     * .subagentFactory} 自建每个可 spawn 的子 agent，注入由父上下文派生的 fail-closed 权限上下文
     * （{@code SubagentPermissions.deriveChildContext}——父 DENY 绑定子、继承的 ASK 降级为 DENY（子无
     * confirmer）、EXPLORE/BYPASS 保留），逃逸已闭合（真实 spawn-path 测试 {@code SubagentDelegationTest}
     * 证明：父 DENY 的工具子 agent 无法执行）。故现在默认开启。仍保留开关，且渠道 / 自主数字员工轨道的子
     * agent 依旧受各自 fail-closed 上下文约束。
     *
     * <p>开启会给模型 schema 增加 {@code agent_spawn}/{@code agent_send}/{@code agent_list}/
     * {@code task_output}/{@code task_cancel}/{@code task_list} 六个工具（每轮少量提示词 token）；
     * {@code enabled: false} 逐字节回到 6a 之前的行为（schema 里没有任何子 agent 工具）。这与 pig 的
     * <b>peer</b> agent（{@code /agent use} 切换活跃 agent）是两个正交概念：peer=切换活跃 agent，
     * subagent=活跃 agent 委派子任务给瞬态子 agent。
     */
    public static final class SubagentsConfig {
        @JsonProperty("enabled") private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
    }

    /**
     * 原生 Plan Mode（{@code plan-mode}，av2）配置——「想清楚再动手」：开启后活跃 agent 可进入一个<b>只读</b>
     * 计划阶段（模型自调 {@code plan_enter}，或 {@code /plan enter}），期间只有只读工具与
     * {@code plan_enter}/{@code plan_write}/{@code plan_exit} 可用，其余（写文件 / 执行命令 / 联网 …）一律被
     * {@code PlanModeMiddleware} 拒绝——判据是每个工具的 {@code AgentTool.isReadOnly()}，而 pig 的只读工具本就
     * 如实声明（{@code readFile}/{@code listDirectory}/{@code loadSkill}/… = {@code readOnly=true}），故读只读工具
     * 放行、可变工具拒绝，<b>零工具改动</b>即生效。计划经 {@code plan_write} 落盘到 {@code plan-dir}（工作区相对，
     * 默认 {@code plans}）下的 {@code PLAN.md}；模型调 {@code plan_exit} 退出计划阶段会触发 <b>HITL 人工确认</b>
     * （复用原生权限 ASK → REPL 的确认提示），批准后方可进入执行阶段——模型无法擅自跳过计划直接执行。
     *
     * <p><b>默认 {@code enabled=false}</b>（保守、零行为变更、逐字节保持系统提示稳定）：关闭时不注册任何 plan 工具、
     * 不安装 {@code PlanModeMiddleware}，与今日行为完全一致；{@code /plan enter} 会提示先在配置里开启。开启后 plan
     * 工具随即出现在<b>交互 agent（及可 {@code /agent use} 的 peer）</b>的 schema；渠道 / 自主数字员工轨道<b>不</b>
     * 开启（无 confirmer，{@code plan_exit} 的 HITL 会 fail-closed，避免卡在计划阶段）。
     *
     * <p><b>与 {@code /permission mode plan}（EXPLORE）的关系</b>：两者是<em>正交</em>的只读机制。EXPLORE 是权限引擎
     * 层的只读（{@code /permission mode plan} 一键把可变工具全部 DENY）；native Plan Mode 是「结构化计划 + 落盘 +
     * HITL 退出」的流程（其只读由 {@code PlanModeMiddleware} 独立强制）。进 / 出 native Plan Mode <b>不</b>改动权限
     * 模式，反之亦然——无冲突状态；plan-mode 的只读在<em>任何</em>权限模式下都成立（BYPASS 下计划阶段仍拒绝可变工具）。
     *
     * <p><b>{@code allow-shell}</b>（默认 false）忠实映射到原生 {@code allowShellInPlanMode(...)}：它把原生 shell 工具名
     * {@code execute} 加入计划阶段白名单。<b>注意</b>：pig 禁用了原生 shell、改用自有 {@code executeCommand}，名称不
     * 匹配，故对 pig 而言 {@code allow-shell=true} 实际<em>不</em>会在计划阶段放行 pig 的 shell（{@code executeCommand}
     * 非只读，仍被拒绝）——只读保证更强，此项对 pig 基本为空操作（已如实文档化）。
     */
    public static final class PlanModeConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("plan-dir") private String planDir = "plans";
        @JsonProperty("allow-shell") private boolean allowShell = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getPlanDir() { return planDir; }
        public void setPlanDir(String d) { this.planDir = d; }
        public boolean isAllowShell() { return allowShell; }
        public void setAllowShell(boolean a) { this.allowShell = a; }
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
