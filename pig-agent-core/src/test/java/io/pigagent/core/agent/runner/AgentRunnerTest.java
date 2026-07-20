package io.pigagent.core.agent.runner;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.PigAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunnerTest {

    /** Fake model: SUCCESS returns text, FAILURE errors, HANG never completes (for timeout). */
    static final class FakeModel implements Model {
        enum Mode { SUCCESS, FAILURE, HANG }
        private final Mode mode;
        FakeModel(Mode mode) { this.mode = mode; }
        @Override public String getModelName() { return "fake"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return switch (mode) {
                case SUCCESS -> Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("跑完测试，全绿").build()))
                        .finishReason("stop").build());
                case FAILURE -> Flux.error(new RuntimeException("boom"));
                case HANG -> Flux.never();
            };
        }
    }

    private PigAgent agent(FakeModel model) {
        return PigAgent.builder().name("t").sysPrompt("s").model(model).build();
    }

    private AgentSpec spec(int timeout) {
        return AgentSpec.create("nightwatch", "代码守夜人")
                .withMandate("跑测试").withSchedule("0 2 * * *").withTimeoutSeconds(timeout);
    }

    @Test
    void success_producesReport_withBody_andWritesBack() {
        List<AgentReport> written = new ArrayList<>();
        AtomicReference<Long> ranAt = new AtomicReference<>();
        AgentRunner runner = new AgentRunner(
                (s, rec) -> agent(new FakeModel(FakeModel.Mode.SUCCESS)),
                (s, r) -> written.add(r),
                (s, epoch) -> ranAt.set(epoch),
                () -> 42L);

        Optional<AgentReport> report = runner.run(spec(0));

        assertThat(report).isPresent();
        assertThat(report.get().outcome()).isEqualTo(AgentReport.Outcome.SUCCESS);
        assertThat(report.get().body()).contains("跑完测试");
        assertThat(written).hasSize(1);
        assertThat(ranAt.get()).isEqualTo(42L);
    }

    @Test
    void failure_producesFailureReport() {
        List<AgentReport> written = new ArrayList<>();
        AgentRunner runner = new AgentRunner(
                (s, rec) -> agent(new FakeModel(FakeModel.Mode.FAILURE)),
                (s, r) -> written.add(r), null, () -> 0L);

        Optional<AgentReport> report = runner.run(spec(0));

        assertThat(report).isPresent();
        assertThat(report.get().outcome()).isEqualTo(AgentReport.Outcome.FAILURE);
        assertThat(report.get().note()).isNotBlank();
        assertThat(written).hasSize(1); // failure still produces a report
    }

    @Test
    void timeout_producesTimeoutReport_withoutHanging() {
        AgentRunner runner = new AgentRunner(
                (s, rec) -> agent(new FakeModel(FakeModel.Mode.HANG)),
                (s, r) -> {}, null, () -> 0L);

        Optional<AgentReport> report = runner.run(spec(1)); // 1s best-effort timeout

        assertThat(report).isPresent();
        assertThat(report.get().outcome()).isEqualTo(AgentReport.Outcome.TIMEOUT);
    }

    @Test
    void deniedActions_appearInPending() {
        AgentRunner runner = new AgentRunner(
                (s, rec) -> {
                    rec.record("executeCommand", "rm -rf /"); // simulate a denied dangerous action
                    return agent(new FakeModel(FakeModel.Mode.SUCCESS));
                },
                (s, r) -> {}, null, () -> 0L);

        AgentReport report = runner.run(spec(0)).orElseThrow();

        assertThat(report.pending()).anyMatch(p -> p.contains("executeCommand"));
        assertThat(report.render()).contains("等你决定").contains("rm -rf /");
    }

    @Test
    void runMandate_success_returnsReport_withoutReportWriterOrSpecUpdater() {
        List<AgentReport> written = new ArrayList<>();
        AtomicReference<Long> ranAt = new AtomicReference<>();
        AgentRunner runner = new AgentRunner(
                (s, rec) -> agent(new FakeModel(FakeModel.Mode.SUCCESS)),
                (s, r) -> written.add(r),
                (s, epoch) -> ranAt.set(epoch),
                () -> 42L);

        AgentReport report = runner.runMandate("task:abc", "跑测试\n\n跑一下夜间测试", 0);

        assertThat(report).isNotNull();
        assertThat(report.outcome()).isEqualTo(AgentReport.Outcome.SUCCESS);
        assertThat(report.body()).contains("跑完测试");
        // Ad-hoc runs must NOT write a morning report or persist a spec (there is none).
        assertThat(written).isEmpty();
        assertThat(ranAt.get()).isNull();
    }

    @Test
    void runMandate_failure_returnsFailureReport_doesNotThrow() {
        AgentRunner runner = new AgentRunner(
                (s, rec) -> agent(new FakeModel(FakeModel.Mode.FAILURE)),
                (s, r) -> {}, null, () -> 0L);

        AgentReport report = runner.runMandate("task:x", "do it");

        assertThat(report.outcome()).isEqualTo(AgentReport.Outcome.FAILURE);
        assertThat(report.note()).isNotBlank();
    }

    @Test
    void reentrancy_skipsOverlappingRun() {
        // The builder re-enters run() for the same spec; the guard must skip the nested run.
        AtomicReference<Optional<AgentReport>> nested = new AtomicReference<>();
        AgentRunner[] holder = new AgentRunner[1];
        AgentRunner runner = new AgentRunner(
                (s, rec) -> {
                    nested.set(holder[0].run(s)); // reentrant call for same id
                    return agent(new FakeModel(FakeModel.Mode.SUCCESS));
                },
                (s, r) -> {}, null, () -> 0L);
        holder[0] = runner;

        Optional<AgentReport> outer = runner.run(spec(0));

        assertThat(outer).isPresent();          // outer completed
        assertThat(nested.get()).isEmpty();     // nested (reentrant) was skipped
    }
}
