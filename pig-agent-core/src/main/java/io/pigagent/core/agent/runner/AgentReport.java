package io.pigagent.core.agent.runner;

import java.util.List;

/**
 * A digital employee's morning report for one autonomous run: what it did / found (the agent's
 * output), plus the dangerous actions it was NOT allowed to take unattended ("等你决定"). Produced
 * on every run — success, failure, or timeout.
 */
public record AgentReport(
        String agentId,
        String agentName,
        Outcome outcome,
        String body,           // the agent's own output (我做了 / 我发现)
        List<String> pending,  // deferred dangerous actions (等你决定)
        String note            // failure/timeout reason, empty on success
) {

    public enum Outcome { SUCCESS, FAILURE, TIMEOUT }

    public AgentReport {
        pending = pending == null ? List.of() : List.copyOf(pending);
        body = body == null ? "" : body;
        note = note == null ? "" : note;
    }

    /** Render the three-section markdown morning report. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 晨报 · ").append(agentName).append(" · ").append(outcome).append("\n\n");
        sb.append("## ✅ 我做了 / 🔍 我发现\n\n");
        if (outcome != Outcome.SUCCESS && !note.isBlank()) {
            sb.append("> ").append(outcome).append("：").append(note).append("\n\n");
        }
        sb.append(body.isBlank() ? "（无输出）" : body).append("\n\n");
        sb.append("## ⏳ 等你决定\n\n");
        if (pending.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (String p : pending) {
                sb.append("- [ ] ").append(p).append("\n");
            }
        }
        return sb.toString();
    }
}
