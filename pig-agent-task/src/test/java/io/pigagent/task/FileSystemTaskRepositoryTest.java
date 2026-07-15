package io.pigagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FileSystemTaskRepositoryTest {

    @TempDir
    Path tasksDir;

    // ---- Group 2: fault tolerance ----

    @Test
    void findAll_skipsFileWithMissingStatus_returnsValidOnes() throws IOException {
        // Arrange — one valid task, one file missing the Status field
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        Task valid = repo.save(Task.create("Good", "desc"));
        writeRaw("broken", "# Broken\n\n- **ID**: broken\n- **Schedule**: ONCE\n\nno status here\n");

        // Act
        List<Task> all = repo.findAll();

        // Assert
        assertThat(all).extracting(Task::id).containsExactly(valid.id());
    }

    @Test
    void findAll_skipsFileWithIllegalStatusEnum() throws IOException {
        // Arrange
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        Task valid = repo.save(Task.create("Good", "desc"));
        writeRaw("bad-enum", "# Bad\n\n- **ID**: bad-enum\n- **Status**: NONSENSE\n- **Schedule**: ONCE\n\nbody\n");

        // Act + Assert — no throw, bad file skipped
        List<Task> all = repo.findAll();
        assertThat(all).extracting(Task::id).containsExactly(valid.id());
    }

    @Test
    void findAll_skipsTruncatedFile_doesNotThrow() throws IOException {
        // Arrange — a file with only a title (no metadata block at all)
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        writeRaw("truncated", "# Only a title\n");

        // Act + Assert
        assertThatCode(repo::findAll).doesNotThrowAnyException();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void findById_returnsEmptyForBadFile() throws IOException {
        // Arrange
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        writeRaw("bad-enum", "# Bad\n\n- **ID**: bad-enum\n- **Status**: NONSENSE\n- **Schedule**: ONCE\n\nbody\n");

        // Act + Assert
        assertThat(repo.findById("bad-enum")).isEmpty();
    }

    // ---- Group 3: lossless round-trip ----

    @Test
    void roundTrip_cron_preservesExpression() {
        // Arrange
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        Task task = new Task("c1", "Nightly", "run the nightwatch", TaskStatus.TODO,
                TaskSchedule.cron("0 2 * * *"),
                Instant.parse("2026-07-14T10:15:30Z"), Instant.parse("2026-07-14T10:15:30Z"), null);

        // Act
        repo.save(task);
        Task loaded = repo.findById("c1").orElseThrow();

        // Assert
        assertThat(loaded.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.CRON);
        assertThat(loaded.schedule().cronExpression()).isEqualTo("0 2 * * *");
    }

    @Test
    void roundTrip_delayed_preservesSecondsTimestampsAndDescription() {
        // Arrange
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        Instant created = Instant.parse("2026-07-01T08:00:00Z");
        Instant updated = Instant.parse("2026-07-02T09:30:00Z");
        Task task = new Task("d1", "Delayed one", "a multi-word description", TaskStatus.IN_PROGRESS,
                TaskSchedule.delayed(3600), created, updated, null);

        // Act
        repo.save(task);
        Task loaded = repo.findById("d1").orElseThrow();

        // Assert
        assertThat(loaded.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.DELAYED);
        assertThat(loaded.schedule().delaySeconds()).isEqualTo(3600L);
        assertThat(loaded.description()).isEqualTo("a multi-word description");
        assertThat(loaded.createdAt()).isEqualTo(created);
        assertThat(loaded.updatedAt()).isEqualTo(updated);
        assertThat(loaded.status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void roundTrip_once_staysOnce() {
        // Arrange
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        Task task = Task.create("Simple", "just once");

        // Act
        repo.save(task);
        Task loaded = repo.findById(task.id()).orElseThrow();

        // Assert
        assertThat(loaded.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.ONCE);
        assertThat(loaded.description()).isEqualTo("just once");
    }

    @Test
    void oldFormat_scheduleTypeOnly_degradesToOnce() throws IOException {
        // Arrange — legacy file: Schedule line has only the type, no cron value
        FileSystemTaskRepository repo = new FileSystemTaskRepository(tasksDir);
        writeRaw("legacy", "# Legacy\n\n- **ID**: legacy\n- **Status**: TODO\n- **Schedule**: CRON\n"
                + "- **Created**: 2026-07-14T00:00:00Z\n- **Updated**: 2026-07-14T00:00:00Z\n\nbody\n");

        // Act
        Task loaded = repo.findById("legacy").orElseThrow();

        // Assert — degraded, not crashed
        assertThat(loaded.schedule().type()).isEqualTo(TaskSchedule.ScheduleType.ONCE);
    }

    @Test
    void scheduleAll_reschedulesReloadedCronTask() {
        // Arrange — a persisted CRON task, then a fresh repository/manager/scheduler as after a restart
        new FileSystemTaskRepository(tasksDir).save(new Task("nightly", "Nightly", "", TaskStatus.TODO,
                TaskSchedule.cron("0 2 * * *"), Instant.now(), Instant.now(), null));
        TaskManager manager = new TaskManager(new FileSystemTaskRepository(tasksDir));
        TaskScheduler scheduler = new TaskScheduler(manager);

        // Act
        scheduler.scheduleAll();

        // Assert — reloaded CRON task is re-scheduled (not degraded to ONCE)
        assertThat(scheduler.isScheduled("nightly")).isTrue();
        scheduler.shutdown();
    }

    private void writeRaw(String id, String content) throws IOException {
        Path dir = tasksDir.resolve("2026-07-14");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".md"), content);
    }
}
