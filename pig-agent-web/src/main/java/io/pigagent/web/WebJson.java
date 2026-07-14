package io.pigagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.kernel.KernelEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps kernel domain types to plain JSON for the Web console. The Web layer only <em>reads</em> the
 * façade — it never annotates core types, so the mapping lives here as explicit {@code Map}
 * projections (stable wire shape, decoupled from core record layout).
 *
 * <p>Credential safety: the projections output only non-sensitive fields (identity, model id, tool
 * count, lifecycle state). No apiKey / token / env / headers ever reach a response.
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
