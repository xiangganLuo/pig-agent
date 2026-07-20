package io.pigagent.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    @Test
    void createsTaskWithDefaultStatus() {
        Task task = Task.create("Test title", "Test description");
        assertThat(task.title()).isEqualTo("Test title");
        assertThat(task.description()).isEqualTo("Test description");
        assertThat(task.status()).isEqualTo(TaskStatus.TODO);
        assertThat(task.id()).isNotBlank();
        assertThat(task.createdAt()).isNotNull();
    }

    @Test
    void withStatusReturnsNewInstance() {
        Task task = Task.create("Test", "Desc");
        Task updated = task.withStatus(TaskStatus.IN_PROGRESS);
        assertThat(updated.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.status()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void withScheduleReturnsNewInstance() {
        Task task = Task.create("Test", "Desc");
        Task scheduled = task.withSchedule(TaskSchedule.cron("0 9 * * *"));
        assertThat(scheduled.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.CRON);
        assertThat(task.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.ONCE);
    }

    @Test
    void freshTaskHasNoRunOutcome() {
        Task task = Task.create("Test", "Desc");
        assertThat(task.result()).isNull();
        assertThat(task.lastRunAt()).isNull();
    }

    @Test
    void withResultReturnsNewInstance_doesNotMutateOriginal() {
        Task task = Task.create("Test", "Desc");
        Task ran = task.withResult("[SUCCESS] done");
        assertThat(ran.result()).isEqualTo("[SUCCESS] done");
        assertThat(task.result()).isNull(); // original untouched
    }

    @Test
    void withLastRunAtReturnsNewInstance() {
        Instant when = Instant.parse("2026-07-20T02:00:00Z");
        Task ran = Task.create("Test", "Desc").withLastRunAt(when);
        assertThat(ran.lastRunAt()).isEqualTo(when);
    }

    @Test
    void withStatusPreservesRunOutcome() {
        Instant when = Instant.parse("2026-07-20T02:00:00Z");
        Task ran = Task.create("Test", "Desc").withResult("[SUCCESS] ok").withLastRunAt(when);

        Task completed = ran.withStatus(TaskStatus.COMPLETED);

        assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.result()).isEqualTo("[SUCCESS] ok");
        assertThat(completed.lastRunAt()).isEqualTo(when);
    }
}
