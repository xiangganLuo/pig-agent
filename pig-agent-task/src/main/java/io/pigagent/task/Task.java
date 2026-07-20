package io.pigagent.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A task. Immutable — mutate via {@code withXxx} copy methods, never in place.
 *
 * <p>{@code result} + {@code lastRunAt} capture the outcome of a scheduled run (task-executor-wiring):
 * when a fired task is dispatched through the one-shot executor, its run summary + timestamp are
 * recorded here (see {@link TaskManager#recordRun}). Both are nullable — a never-run task, and any
 * task loaded from an old {@code .md} without these fields, have {@code null} for both.
 */
public record Task(
        String id, String title, String description, TaskStatus status,
        TaskSchedule schedule, Instant createdAt, Instant updatedAt, LocalDate dueDate,
        String result, Instant lastRunAt
) {
    /** Backward-compatible 8-arg constructor (no run outcome): {@code result}/{@code lastRunAt} = null. */
    public Task(String id, String title, String description, TaskStatus status,
                TaskSchedule schedule, Instant createdAt, Instant updatedAt, LocalDate dueDate) {
        this(id, title, description, status, schedule, createdAt, updatedAt, dueDate, null, null);
    }

    public static Task create(String title, String description) {
        Instant now = Instant.now();
        return new Task(UUID.randomUUID().toString().substring(0, 8), title, description,
                TaskStatus.TODO, TaskSchedule.once(), now, now, null);
    }

    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, title, description, newStatus, schedule, createdAt, Instant.now(), dueDate,
                result, lastRunAt);
    }

    public Task withSchedule(TaskSchedule newSchedule) {
        return new Task(id, title, description, status, newSchedule, createdAt, Instant.now(), dueDate,
                result, lastRunAt);
    }

    /** Copy with the recorded run summary set. */
    public Task withResult(String newResult) {
        return new Task(id, title, description, status, schedule, createdAt, Instant.now(), dueDate,
                newResult, lastRunAt);
    }

    /** Copy with the last-run timestamp set. */
    public Task withLastRunAt(Instant when) {
        return new Task(id, title, description, status, schedule, createdAt, Instant.now(), dueDate,
                result, when);
    }
}
