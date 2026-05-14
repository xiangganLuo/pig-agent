package io.pigagent.task;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    Task save(Task task);
    Optional<Task> findById(String id);
    List<Task> findAll();
    List<Task> findByStatus(TaskStatus status);
    void deleteById(String id);
}
