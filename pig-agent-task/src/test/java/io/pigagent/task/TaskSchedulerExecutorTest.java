package io.pigagent.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The optional task-executor seam: when a {@code Consumer<Task>} is wired, {@code executeTask}
 * delegates real work to it; when unset (default) a scheduled task is a reminder-only marker that
 * keeps the historical {@code TODO→IN_PROGRESS→COMPLETED} status flip (behavior-neutral). Also
 * covers {@link TaskScheduler#isValidCron} used by the REPL's {@code /agent new --schedule}.
 */
class TaskSchedulerExecutorTest {

    private static Task delayedNow() {
        return Task.create("t", "d").withSchedule(TaskSchedule.delayed(0));
    }

    @Test
    void executor_isInvokedWithTheTask_whenSet() throws InterruptedException {
        // Arrange
        TaskManager manager = mock(TaskManager.class);
        TaskScheduler scheduler = new TaskScheduler(manager);
        AtomicReference<Task> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.setTaskExecutor(task -> {
            seen.set(task);
            latch.countDown();
        });
        Task task = delayedNow();

        // Act
        scheduler.schedule(task);

        // Assert
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get().id()).isEqualTo(task.id());
        verify(manager, timeout(3000)).updateStatus(task.id(), TaskStatus.COMPLETED);
        scheduler.shutdown();
    }

    @Test
    void defaultStatusFlip_isPreserved_whenExecutorUnset() {
        // Arrange
        TaskManager manager = mock(TaskManager.class);
        TaskScheduler scheduler = new TaskScheduler(manager);
        Task task = delayedNow();

        // Act — no executor wired: reminder-only marker, no real work
        scheduler.schedule(task);

        // Assert — unchanged legacy behavior: IN_PROGRESS then COMPLETED
        verify(manager, timeout(3000)).updateStatus(task.id(), TaskStatus.IN_PROGRESS);
        verify(manager, timeout(3000)).updateStatus(task.id(), TaskStatus.COMPLETED);
        scheduler.shutdown();
    }

    @Test
    void executorFailure_revertsToTodo() {
        // Arrange
        TaskManager manager = mock(TaskManager.class);
        TaskScheduler scheduler = new TaskScheduler(manager);
        scheduler.setTaskExecutor(t -> {
            throw new IllegalStateException("boom");
        });
        Task task = delayedNow();

        // Act
        scheduler.schedule(task);

        // Assert
        verify(manager, timeout(3000)).updateStatus(task.id(), TaskStatus.TODO);
        verify(manager, never()).updateStatus(task.id(), TaskStatus.COMPLETED);
        scheduler.shutdown();
    }

    @Test
    void isValidCron_acceptsFiveField_rejectsOthers() {
        assertThat(TaskScheduler.isValidCron("0 2 * * *")).isTrue();
        assertThat(TaskScheduler.isValidCron("garbage")).isFalse();
        assertThat(TaskScheduler.isValidCron("*/30")).isFalse(); // bare interval, not a 5-field cron
        assertThat(TaskScheduler.isValidCron(null)).isFalse();
        assertThat(TaskScheduler.isValidCron("   ")).isFalse();
    }
}
