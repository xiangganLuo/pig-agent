package io.pigagent.core.agent.runner;

import io.pigagent.core.agent.AgentSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link CompositeReportWriter} fans out to all delegates and tolerates a throwing one. */
class CompositeReportWriterTest {

    private static AgentReport sampleReport() {
        return new AgentReport("id", "Nightwatch", AgentReport.Outcome.SUCCESS, "did stuff", List.of(), "");
    }

    @Test
    void fansOutToAllDelegates() {
        List<String> calls = new ArrayList<>();
        AgentRunner.ReportWriter a = (spec, report) -> calls.add("a");
        AgentRunner.ReportWriter b = (spec, report) -> calls.add("b");
        CompositeReportWriter composite = new CompositeReportWriter(a, b);

        composite.write(AgentSpec.create("id", "Nightwatch"), sampleReport());

        assertThat(calls).containsExactly("a", "b");
    }

    @Test
    void aThrowingDelegateDoesNotStopTheOthers() {
        List<String> calls = new ArrayList<>();
        AgentRunner.ReportWriter boom = (spec, report) -> {
            throw new RuntimeException("disk full");
        };
        AgentRunner.ReportWriter ok = (spec, report) -> calls.add("ok");
        CompositeReportWriter composite = new CompositeReportWriter(boom, ok);

        composite.write(AgentSpec.create("id", "Nightwatch"), sampleReport());

        assertThat(calls).containsExactly("ok");
    }
}
