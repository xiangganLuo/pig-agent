package io.pigagent.task;

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
}
