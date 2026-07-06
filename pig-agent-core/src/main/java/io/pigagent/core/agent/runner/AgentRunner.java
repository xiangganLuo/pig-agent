package io.pigagent.core.agent.runner;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
            return new AgentReport(spec.id(), spec.name(), AgentReport.Outcome.SUCCESS,
                    text, recorder.denied(), "");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new AgentReport(spec.id(), spec.name(), AgentReport.Outcome.FAILURE,
                    "", recorder.denied(), cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }
}
