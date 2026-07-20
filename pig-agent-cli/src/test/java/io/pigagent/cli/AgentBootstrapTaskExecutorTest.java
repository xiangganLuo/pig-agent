package io.pigagent.cli;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.runner.AgentReport;
import io.pigagent.core.agent.runner.AgentRunner;
import io.pigagent.task.FileSystemTaskRepository;
import io.pigagent.task.Task;
import io.pigagent.task.TaskManager;
import io.pigagent.task.TaskSchedule;
import io.pigagent.task.TaskScheduler;
import io.pigagent.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline, deterministic coverage of the task-executor wiring (task-executor-wiring):
 * {@link AgentBootstrap#buildTaskExecutor} runs a fired task's intent through a real
 * {@link AgentRunner} (fake-model builder, so no network) and records the outcome onto the task, and
 * {@link AgentBootstrap#taskOutcomeSummary} renders the compact stored summary. Also verifies the
 * executor fires through the real {@link TaskScheduler} on a scheduled task.
 */
class AgentBootstrapTaskExecutorTest {

    static final class FakeModel implements Model {
        @Override public String getModelName() { return "fake"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("跑完测试，全绿").build()))
                    .finishReason("stop").build());
        }
    }

    private AgentRunner successRunner() {
        return new AgentRunner(
                (s, rec) -> PigAgent.builder().name(s.name()).sysPrompt(s.sysPrompt())
                        .model(new FakeModel()).build(),
                (s, r) -> { /* no report writer for ad-hoc runs */ },
                null, null);
    }

    @Test
    void executor_runsIntent_andCapturesOutcomeOnTask(@TempDir Path root) {
        // Arrange — real repository/manager + a real runner with a fake (offline) model
        TaskManager manager = new TaskManager(new FileSystemTaskRepository(root.resolve("tasks")));
        Task task = manager.createScheduledTask("Nightly tests", "run mvn test", TaskSchedule.delayed(60));
        Consumer<Task> executor = AgentBootstrap.buildTaskExecutor(successRunner(), manager);

        // Act
        executor.accept(task);

        // Assert — the run outcome (SUCCESS + the model's text) is recorded on the task
        Task reloaded = manager.getTask(task.id()).orElseThrow();
        assertThat(reloaded.result()).contains("[SUCCESS]").contains("跑完测试");
        assertThat(reloaded.lastRunAt()).isNotNull();
    }

    @Test
    void scheduler_firesExecutor_andCapturesOutcome_thenCompletes(@TempDir Path root) {
        // Arrange — wire the executor into a real scheduler and schedule an immediate (DELAYED 0) fire
        TaskManager manager = new TaskManager(new FileSystemTaskRepository(root.resolve("tasks")));
        TaskScheduler scheduler = new TaskScheduler(manager);
        scheduler.setTaskExecutor(AgentBootstrap.buildTaskExecutor(successRunner(), manager));
        Task task = manager.createScheduledTask("Nightly", "run tests", TaskSchedule.delayed(0));

        // Act
        scheduler.schedule(task);

        // Assert — the task ends COMPLETED with its run outcome captured
        // (poll the file store until the scheduler thread has recorded + completed).
        assertThat(pollResult(manager, task.id())).contains("[SUCCESS]");
        assertThat(manager.getTask(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        scheduler.shutdown();
    }

    private static String pollResult(TaskManager manager, String id) {
        for (int i = 0; i < 60; i++) {
            Task t = manager.getTask(id).orElse(null);
            if (t != null && t.status() == TaskStatus.COMPLETED && t.result() != null) {
                return t.result();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Task t = manager.getTask(id).orElse(null);
        return t == null ? "" : String.valueOf(t.result());
    }

    @Test
    void taskOutcomeSummary_success_usesBody() {
        AgentReport report = new AgentReport("id", "name", AgentReport.Outcome.SUCCESS,
                "did the thing", List.of(), "");
        assertThat(AgentBootstrap.taskOutcomeSummary(report)).isEqualTo("[SUCCESS] did the thing");
    }

    @Test
    void taskOutcomeSummary_failure_usesNote() {
        AgentReport report = new AgentReport("id", "name", AgentReport.Outcome.FAILURE,
                "", List.of(), "boom");
        assertThat(AgentBootstrap.taskOutcomeSummary(report)).isEqualTo("[FAILURE] boom");
    }

    @Test
    void taskOutcomeSummary_successNoBody_showsPlaceholder() {
        AgentReport report = new AgentReport("id", "name", AgentReport.Outcome.SUCCESS,
                "", List.of(), "");
        assertThat(AgentBootstrap.taskOutcomeSummary(report)).isEqualTo("[SUCCESS] (no output)");
    }
}
