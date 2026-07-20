package io.pigagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class TaskManager {
    private final TaskRepository repository;

    public TaskManager(TaskRepository repository) { this.repository = repository; }

    public Task createTask(String title, String description) {
        return repository.save(Task.create(title, description));
    }

    public Task createScheduledTask(String title, String description, TaskSchedule schedule) {
        return repository.save(Task.create(title, description).withSchedule(schedule));
    }

    public Optional<Task> getTask(String id) { return repository.findById(id); }
    public List<Task> getAllTasks() { return repository.findAll(); }
    public List<Task> getTasksByStatus(TaskStatus status) { return repository.findByStatus(status); }

    public Task updateStatus(String id, TaskStatus newStatus) {
        Task task = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
        return repository.save(task.withStatus(newStatus));
    }

    public void deleteTask(String id) { repository.deleteById(id); }

    /**
     * Record a scheduled run's outcome on the task ({@code result} + {@code lastRunAt=now}), preserving
     * its current status/schedule. Fault-tolerant: a task that no longer exists is a no-op (never throws),
     * so a run that finishes after the task was deleted does not blow up the scheduler thread.
     */
    public void recordRun(String id, String result) {
        repository.findById(id)
                .ifPresent(t -> repository.save(t.withResult(result).withLastRunAt(Instant.now())));
    }
}
