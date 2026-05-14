package io.pigagent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public final class PigAgentConfig {

    @JsonProperty("workspace") private String workspacePath;
    @JsonProperty("model") private ModelConfig model = new ModelConfig();
    @JsonProperty("agent") private AgentConfig agent = new AgentConfig();
    @JsonProperty("channels") private Map<String, ChannelConfig> channels = Map.of();
    @JsonProperty("mcp") private McpConfig mcp = new McpConfig();

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String path) { this.workspacePath = path; }
    public ModelConfig getModel() { return model; }
    public AgentConfig getAgent() { return agent; }
    public Map<String, ChannelConfig> getChannels() { return channels; }
    public McpConfig getMcp() { return mcp; }

    public static final class ModelConfig {
        @JsonProperty("provider") private String provider = "mimo";
        @JsonProperty("model-name") private String modelName = "mimo-v2.5-pro";
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

    public static final class McpConfig {
        @JsonProperty("servers") private Map<String, McpServerConfig> servers = Map.of();
        public Map<String, McpServerConfig> getServers() { return servers; }
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
