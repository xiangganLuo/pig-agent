package io.pigagent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public final class PigAgentConfig {

    @JsonProperty("workspace") private String workspacePath;
    @JsonProperty("model") private ModelConfig model = new ModelConfig();
    @JsonProperty("agent") private AgentConfig agent = new AgentConfig();
    @JsonProperty("channels") private Map<String, ChannelConfig> channels = Map.of();
    @JsonProperty("mcp") private McpConfig mcp = new McpConfig();
    @JsonProperty("compression") private CompressionConfig compression = new CompressionConfig();
    @JsonProperty("current-session-id") private String currentSessionId;
    @JsonProperty("memory-enabled") private boolean memoryEnabled = true;

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String path) { this.workspacePath = path; }
    public ModelConfig getModel() { return model; }
    public AgentConfig getAgent() { return agent; }
    public Map<String, ChannelConfig> getChannels() { return channels; }
    public McpConfig getMcp() { return mcp; }
    public CompressionConfig getCompression() { return compression; }
    public String getCurrentSessionId() { return currentSessionId; }
    public void setCurrentSessionId(String id) { this.currentSessionId = id; }
    public boolean isMemoryEnabled() { return memoryEnabled; }
    public void setMemoryEnabled(boolean enabled) { this.memoryEnabled = enabled; }

    public static final class ModelConfig {
        @JsonProperty("provider") private String provider = "anthropic";
        @JsonProperty("model-name") private String modelName = "claude-sonnet-4-6";
        public String getProvider() { return provider; }
        public void setProvider(String p) { this.provider = p; }
        public String getModelName() { return modelName; }
        public void setModelName(String n) { this.modelName = n; }
    }

    public static final class AgentConfig {
        @JsonProperty("name") private String name = "PigAgent";
        @JsonProperty("max-iters") private int maxIters = 10;
        public String getName() { return name; }
        public void setName(String n) { this.name = n; }
        public int getMaxIters() { return maxIters; }
        public void setMaxIters(int i) { this.maxIters = i; }
    }

    public static final class ChannelConfig {
        @JsonProperty("enabled") private boolean enabled = false;
        @JsonProperty("token") private String token;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public String getToken() { return token; }
        public void setToken(String t) { this.token = t; }
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
