package io.pigagent.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class FileSystemTaskRepository implements TaskRepository {
    private final Path tasksDir;

    public FileSystemTaskRepository(Path tasksDir) { this.tasksDir = tasksDir; }

    @Override
    public Task save(Task task) {
        try {
            Path dateDir = tasksDir.resolve(LocalDate.now().toString());
            Files.createDirectories(dateDir);
            Files.writeString(dateDir.resolve(task.id() + ".md"), toMarkdown(task));
            return task;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task: " + task.id(), e);
        }
    }

    @Override
    public Optional<Task> findById(String id) {
        try (Stream<Path> walk = Files.walk(tasksDir)) {
            return walk.filter(p -> p.getFileName().toString().equals(id + ".md"))
                    .findFirst().map(this::parseMarkdown);
        } catch (IOException e) { return Optional.empty(); }
    }

    @Override
    public List<Task> findAll() {
        List<Task> tasks = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tasksDir)) {
            walk.filter(p -> p.toString().endsWith(".md")).map(this::parseMarkdown).forEach(tasks::add);
        } catch (IOException ignored) {}
        return tasks;
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return findAll().stream().filter(t -> t.status() == status).toList();
    }

    @Override
    public void deleteById(String id) {
        try (Stream<Path> walk = Files.walk(tasksDir)) {
            walk.filter(p -> p.getFileName().toString().equals(id + ".md")).findFirst()
                    .ifPresent(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private String toMarkdown(Task t) {
        return "# %s\n\n- **ID**: %s\n- **Status**: %s\n- **Schedule**: %s\n- **Created**: %s\n- **Updated**: %s\n\n%s\n"
                .formatted(t.title(), t.id(), t.status(), t.schedule().type(), t.createdAt(), t.updatedAt(), t.description());
    }

    private Task parseMarkdown(Path path) {
        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n");
            String title = lines.length > 0 && lines[0].startsWith("# ") ? lines[0].substring(2).trim() : "Untitled";
            String id = extractField(content, "ID");
            TaskStatus status = TaskStatus.valueOf(extractField(content, "Status"));
            return new Task(id != null ? id : path.getFileName().toString().replace(".md", ""),
                    title, "", status, TaskSchedule.once(), Instant.now(), Instant.now(), null);
        } catch (IOException e) { return Task.create("Error", ""); }
    }

    private String extractField(String content, String field) {
        for (String line : content.split("\n")) {
            if (line.contains("**" + field + "**:")) {
                int idx = line.indexOf(":");
                return idx >= 0 ? line.substring(idx + 1).trim() : null;
            }
        }
        return null;
    }
}
