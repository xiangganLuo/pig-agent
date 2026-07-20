package io.pigagent.core.agent.runner;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.PigAgent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Runs a digital-employee agent's mandate once, unattended, and produces a morning report.
 *
 * <p>Correctness (see digital-employee design):
 * <ul>
 *   <li><b>Isolated one-shot agent</b>: the agent is built fresh per run (via {@link AgentBuilder},
 *       independent memory) — never the interactive active instance, so the user's session is
 *       untouched.</li>
 *   <li><b>Reentrancy guard</b>: a run already in flight for the same agent is skipped.</li>
 *   <li><b>Best-effort timeout</b> (D4): run on a background thread + {@code Future.get(timeout)} +
 *       {@code cancel}; because {@code ReActAgent} is not truly interruptible, timeout marks the
 *       run as TIMEOUT and produces a report but cannot guarantee the underlying call stops. We do
 *       NOT re-subscribe/re-enter the agent (that was the "Agent is still running" bug).</li>
 *   <li><b>Always reports</b>: success, failure, and timeout each produce a report; {@code lastRunAt}
 *       is written back via {@link SpecUpdater}.</li>
 * </ul>
 */
public final class AgentRunner {

    /** Builds the isolated one-shot agent for a spec, wiring the recorder to its permission layer. */
    @FunctionalInterface
    public interface AgentBuilder {
        PigAgent build(AgentSpec spec, DeniedActionRecorder recorder);
    }

    /** Persists a produced report (e.g. to {@code workspace/reports/{date}/{id}.md}). */
    @FunctionalInterface
    public interface ReportWriter {
        void write(AgentSpec spec, AgentReport report);
    }

    /** Writes back {@code lastRunAt} after a run (e.g. persist the updated spec). */
    @FunctionalInterface
    public interface SpecUpdater {
        void markRan(AgentSpec spec, long epochMs);
    }

    private final AgentBuilder builder;
    private final ReportWriter reportWriter;
    private final SpecUpdater specUpdater; // nullable
    private final LongSupplier clock;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-runner");
        t.setDaemon(true);
        return t;
    });

    public AgentRunner(AgentBuilder builder, ReportWriter reportWriter, SpecUpdater specUpdater,
                       LongSupplier clock) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
        this.specUpdater = specUpdater;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Default best-effort timeout (seconds) for an ad-hoc mandate run when the caller passes none. */
    public static final int DEFAULT_MANDATE_TIMEOUT_SECONDS = 300;

    /**
     * Run an <em>ad-hoc</em> mandate string once, unattended, reusing the same fail-closed one-shot
     * machinery as {@link #run(AgentSpec)} (isolated agent via the {@link AgentBuilder}, reentrancy
     * guard, best-effort timeout, post-hoc DENIED scan) — but WITHOUT the morning-report write /
     * {@code lastRunAt} persistence, because an ad-hoc run has no persistent {@link AgentSpec}. Used to
     * execute a scheduled task's intent (task-executor-wiring). Never throws: a run failure/timeout
     * comes back as a {@link AgentReport} with the matching outcome. Uses {@link
     * #DEFAULT_MANDATE_TIMEOUT_SECONDS}.
     */
    public AgentReport runMandate(String id, String mandate) {
        return runMandate(id, mandate, DEFAULT_MANDATE_TIMEOUT_SECONDS);
    }

    /** {@link #runMandate(String, String)} with an explicit best-effort timeout ({@code <= 0} = none). */
    public AgentReport runMandate(String id, String mandate, int timeoutSeconds) {
        Objects.requireNonNull(id, "id");
        AgentSpec spec = AgentSpec.create(id, id)
                .withMandate(mandate == null ? "" : mandate)
                .withTimeoutSeconds(Math.max(0, timeoutSeconds));
        if (!running.add(id)) {
            // A run for this id is already in progress — report it rather than double-running.
            return new AgentReport(id, id, AgentReport.Outcome.FAILURE, "",
                    java.util.List.of(), "a run for this id is already in progress");
        }
        try {
            DeniedActionRecorder recorder = new DeniedActionRecorder();
            PigAgent agent = builder.build(spec, recorder);
            return execute(spec, agent, recorder);
        } finally {
            running.remove(id);
        }
    }

    /**
     * Run the agent's mandate once. Returns the produced report, or empty if skipped due to a
     * reentrant run already in progress for this agent.
     */
    public Optional<AgentReport> run(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (!running.add(spec.id())) {
            return Optional.empty(); // reentrancy: an earlier run is still in progress
        }
        try {
            DeniedActionRecorder recorder = new DeniedActionRecorder();
            PigAgent agent = builder.build(spec, recorder);
            AgentReport report = execute(spec, agent, recorder);
            reportWriter.write(spec, report);
            if (specUpdater != null) {
                specUpdater.markRan(spec, clock.getAsLong());
            }
            return Optional.of(report);
        } finally {
            running.remove(spec.id());
        }
    }

    private AgentReport execute(AgentSpec spec, PigAgent agent, DeniedActionRecorder recorder) {
        Msg mandate = Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(spec.mandate() == null ? "" : spec.mandate()).build())
                .build();
        int timeout = spec.timeoutSeconds();
        try {
            Msg reply;
            if (timeout > 0) {
                Future<Msg> future = pool.submit(() -> agent.call(mandate));
                try {
                    reply = future.get(timeout, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true); // best-effort; ReActAgent may not truly stop
                    return new AgentReport(spec.id(), spec.name(), AgentReport.Outcome.TIMEOUT,
                            "", recorder.denied(), "超过 " + timeout + "s 未完成");
                }
            } else {
                reply = agent.call(mandate);
            }
            String text = reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
            // av2 Phase 4: with native permission the denial has no build-time callback — recover the
            // denied actions post-hoc from the conversation (DENIED tool-results the ReAct loop fed
            // back), so the report's「等你决定」section still itemizes them. Best-effort, never fatal.
            recordDenials(agent, recorder);
            return new AgentReport(spec.id(), spec.name(), AgentReport.Outcome.SUCCESS,
                    text, recorder.denied(), "");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new AgentReport(spec.id(), spec.name(), AgentReport.Outcome.FAILURE,
                    "", recorder.denied(), cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    /**
     * Scan the finished conversation for tool results the native permission engine marked
     * {@link ToolResultState#DENIED} and feed each into the {@link DeniedActionRecorder}. This is the
     * av2 replacement for the deleted {@code ToolPermissionHook}'s per-denial callback — native denial
     * has no build-time seam, but the ReAct loop records a DENIED {@link ToolResultBlock} for every
     * vetoed call, so a post-run scan reconstructs the same「等你决定」list. Fault-tolerant by design.
     */
    private void recordDenials(PigAgent agent, DeniedActionRecorder recorder) {
        try {
            for (Msg m : agent.getMemory().getMessages()) {
                for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                    if (r.getState() == ToolResultState.DENIED) {
                        recorder.record(r.getName() == null ? "tool" : r.getName(),
                                "denied by permission policy");
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort: a denial-scan failure must never break the report.
        }
    }
}
