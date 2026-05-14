package io.pigagent.task;

import org.junit.jupiter.api.Test;
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
}
