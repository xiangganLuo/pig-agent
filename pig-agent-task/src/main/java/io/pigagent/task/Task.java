package io.pigagent.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Task(
        String id, String title, String description, TaskStatus status,
        TaskSchedule schedule, Instant createdAt, Instant updatedAt, LocalDate dueDate
) {
    public static Task create(String title, String description) {
        Instant now = Instant.now();
        return new Task(UUID.randomUUID().toString().substring(0, 8), title, description,
                TaskStatus.TODO, TaskSchedule.once(), now, now, null);
    }

    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, title, description, newStatus, schedule, createdAt, Instant.now(), dueDate);
    }

    public Task withSchedule(TaskSchedule newSchedule) {
        return new Task(id, title, description, status, newSchedule, createdAt, Instant.now(), dueDate);
    }
}
