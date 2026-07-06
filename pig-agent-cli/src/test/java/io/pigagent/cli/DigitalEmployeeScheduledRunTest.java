package io.pigagent.cli;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.runner.AgentRunner;
import io.pigagent.core.agent.runner.FileReportWriter;
import io.pigagent.task.TaskSchedule;
import io.pigagent.task.TaskScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (offline, deterministic): a scheduled autonomous agent runs through the real
 * {@link TaskScheduler} → {@link AgentRunner} → {@link FileReportWriter} path and lands a morning
 * report file. No live model — a fake model drives the run.
 */
class DigitalEmployeeScheduledRunTest {

    static final class FakeModel implements Model {
        @Override public String getModelName() { return "fake"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("跑完测试，全绿").build()))
                    .finishReason("stop").build());
        }
    }

    @Test
    void scheduledAgent_runs_andWritesReport(@TempDir Path reports) throws InterruptedException, java.io.IOException {
        AgentSpec spec = AgentSpec.create("nightwatch", "代码守夜人")
                .withMandate("跑测试").withSchedule("0 2 * * *");

        AgentRunner runner = new AgentRunner(
                (s, rec) -> PigAgent.builder().name(s.name()).sysPrompt(s.sysPrompt())
                        .model(new FakeModel()).build(),
                new FileReportWriter(reports),
                null, null);

        TaskScheduler scheduler = new TaskScheduler(null);
        CountDownLatch done = new CountDownLatch(1);

        // Schedule an immediate run (DELAYED 0) through the real scheduler path.
        scheduler.schedule("agent:" + spec.id(), TaskSchedule.delayed(0), () -> {
            runner.run(spec);
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        scheduler.shutdown();

        Path reportFile = reports.resolve(LocalDate.now().toString()).resolve("nightwatch.md");
        assertThat(Files.exists(reportFile)).isTrue();
        String content = Files.readString(reportFile);
        assertThat(content).contains("我做了").contains("跑完测试").contains("等你决定");
    }
}
