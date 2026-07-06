package io.pigagent.core.agent.runner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects the dangerous actions an unattended agent was NOT allowed to perform, so they can be
 * surfaced in the morning report's「等你决定」section. The permission layer feeds it via
 * {@link #record}; the runner reads {@link #denied} after the run.
 */
public final class DeniedActionRecorder {

    private final List<String> denied = new CopyOnWriteArrayList<>();

    /** Record a denied action, e.g. record("executeCommand", "rm -rf ..."). */
    public void record(String toolName, String detail) {
        String d = (detail == null || detail.isBlank())
                ? toolName : toolName + "：" + detail.strip();
        denied.add(d);
    }

    public List<String> denied() {
        return List.copyOf(denied);
    }
}
