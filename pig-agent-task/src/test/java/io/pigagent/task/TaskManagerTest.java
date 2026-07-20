package io.pigagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link TaskManager} orchestration over a real file-backed repository ({@code @TempDir}).
 * Focus: {@code recordRun} captures a scheduled run's outcome (task-executor-wiring) without
 * disturbing status/schedule, and is a fault-tolerant no-op for an unknown task.
 */
class TaskManagerTest {

    @TempDir
    Path tasksDir;

    @Test
    void recordRun_setsResultAndLastRunAt_preservingStatus() {
        // Arrange
        TaskManager manager = new TaskManager(new FileSystemTaskRepository(tasksDir));
        Task task = manager.createScheduledTask("Nightly", "run tests", TaskSchedule.delayed(60));

        // Act
        manager.recordRun(task.id(), "[SUCCESS] all green");

        // Assert
        Task loaded = manager.getTask(task.id()).orElseThrow();
        assertThat(loaded.result()).isEqualTo("[SUCCESS] all green");
        assertThat(loaded.lastRunAt()).isNotNull();
        assertThat(loaded.status()).isEqualTo(TaskStatus.TODO); // status untouched by recordRun
        assertThat(loaded.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.DELAYED);
    }

    @Test
    void recordRun_unknownTask_isNoOp_doesNotThrow() {
        TaskManager manager = new TaskManager(new FileSystemTaskRepository(tasksDir));

        assertThatCode(() -> manager.recordRun("does-not-exist", "x")).doesNotThrowAnyException();
        assertThat(manager.getTask("does-not-exist")).isEmpty();
    }
}
