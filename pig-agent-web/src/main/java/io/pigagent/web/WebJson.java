package io.pigagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pigagent.config.PigAgentConfig.PermissionConfig;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.kernel.KernelEvent;
import io.pigagent.core.compression.CompressionStatus;
import io.pigagent.core.protocol.ModelProtocol;
import io.pigagent.mcp.McpManager;
import io.pigagent.model.StoredModel;
import io.pigagent.session.Session;
import io.pigagent.task.Task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps kernel domain types to plain JSON for the Web console. The Web layer only *reads* the
 * façade — it never annotates core types, so the mapping lives here as explicit {@code Map}
 * projections (stable wire shape, decoupled from core record layout).
 */
public final class WebJson {

    private final ObjectMapper mapper = new ObjectMapper();

    /** A single agent as the console sees it (identity + model/tools + active/schedule state). */
    public Map<String, Object> agent(AgentInstance inst, String activeId) {
        AgentSpec s = inst.spec();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inst.id());
        m.put("name", s.name());
        m.put("model", s.modelId() == null ? "(default)" : s.modelId());
        m.put("tools", s.usesAllTools() ? "all" : String.valueOf(s.toolNames().size()));
        m.put("active", inst.id().equals(activeId));
        m.put("autonomous", s.isAutonomous());
        m.put("schedule", s.schedule() == null ? "" : s.schedule());
        m.put("state", inst.state().name());
        return m;
    }

    /** A kernel lifecycle event for the SSE stream. */
    public Map<String, Object> event(KernelEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", e.type().name());
        m.put("agentId", e.agentId());
        m.put("message", e.message());
        return m;
    }

    /** A saved model — never includes the API key (secret stays server-side). */
    public Map<String, Object> model(StoredModel m, String defaultId, String currentId) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.id());
        o.put("protocolId", m.protocolId());
        o.put("modelName", m.modelName());
        o.put("baseUrl", m.baseUrl() == null ? "" : m.baseUrl());
        o.put("label", m.label());
        o.put("isDefault", m.id().equals(defaultId));
        o.put("isCurrent", m.id().equals(currentId));
        o.put("requiresApiKey", m.apiKey() != null && !m.apiKey().isBlank());
        return o;
    }

    public Map<String, Object> protocol(ModelProtocol p) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("protocolId", p.protocolId());
        o.put("displayName", p.displayName());
        o.put("description", p.description());
        o.put("requiresApiKey", p.requiresApiKey());
        o.put("supportsBaseUrl", p.supportsBaseUrl());
        o.put("defaultModelName", p.defaultModelName());
        return o;
    }

    public Map<String, Object> session(Session s, String currentId) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", s.id());
        o.put("name", s.name());
        o.put("modelId", s.modelId() == null ? "" : s.modelId());
        o.put("createdAt", String.valueOf(s.createdAt()));
        o.put("lastActiveAt", String.valueOf(s.lastActiveAt()));
        o.put("corrupt", s.corrupt());
        o.put("current", s.id().equals(currentId));
        return o;
    }

    public Map<String, Object> task(Task t) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", t.id());
        o.put("title", t.title());
        o.put("description", t.description());
        o.put("status", t.status().name());
        o.put("createdAt", String.valueOf(t.createdAt()));
        o.put("updatedAt", String.valueOf(t.updatedAt()));
        return o;
    }

    /** MCP server status — env/headers are NOT included (credential redaction). */
    public Map<String, Object> mcp(McpManager.ServerStatus st) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", st.spec().name());
        o.put("transport", st.spec().transport());
        o.put("label", st.spec().label());
        o.put("command", st.spec().command() == null ? "" : st.spec().command());
        o.put("url", st.spec().url() == null ? "" : st.spec().url());
        o.put("enabled", st.spec().enabled());
        o.put("connected", st.connected());
        o.put("toolCount", st.toolCount());
        return o;
    }

    public Map<String, Object> permission(PermissionConfig p) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("mode", p.resolveMode().name().toLowerCase());
        o.put("channelMode", p.resolveChannelMode().name().toLowerCase());
        o.put("tools", p.getAllowlist().getTools());
        o.put("commands", p.getAllowlist().getCommands());
        return o;
    }

    public Map<String, Object> compressStatus(CompressionStatus s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", s.enabled());
        o.put("estimatedTokens", s.estimatedTokens());
        o.put("budgetTokens", s.budgetTokens());
        o.put("thresholdTokens", s.thresholdTokens());
        o.put("messageCount", s.messageCount());
        o.put("lastCompressedEpochMs", s.lastCompressedEpochMs());
        return o;
    }

    /** One chat SSE frame: an event type + its text (empty for control frames like DONE). */
    public Map<String, Object> chatFrame(String type, String text) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", type);
        o.put("text", text == null ? "" : text);
        return o;
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            // Payloads here are plain Maps/Lists of strings/booleans — serialization cannot
            // realistically fail; surface a valid JSON error object rather than throwing.
            return "{\"error\":\"serialization failed\"}";
        }
    }

    /** Parse a small request body into a map; returns an empty map on blank/invalid input. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(body, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
