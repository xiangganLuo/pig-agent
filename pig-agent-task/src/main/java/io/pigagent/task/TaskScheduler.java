package io.pigagent.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes scheduled tasks using a background thread pool.
 * Supports ONCE (one-shot), DELAYED (delayed one-shot), and CRON (recurring) schedules.
 */
public final class TaskScheduler {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final TaskManager taskManager;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public TaskScheduler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public void schedule(Task task) {
        if (task.schedule() == null || task.schedule().type() == TaskSchedule.ScheduleType.ONCE) {
            return;
        }
        cancel(task.id());

        Runnable runnable = () -> executeTask(task);
        ScheduledFuture<?> future;

        switch (task.schedule().type()) {
            case DELAYED -> {
                long seconds = task.schedule().delaySeconds() != null ? task.schedule().delaySeconds() : 60;
                future = executor.schedule(runnable, seconds, TimeUnit.SECONDS);
            }
            case CRON -> {
                long intervalSeconds = parseCronInterval(task.schedule().cronExpression());
                future = executor.scheduleAtFixedRate(runnable, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            }
            default -> { return; }
        }

        scheduledTasks.put(task.id(), future);
        System.err.println("[Scheduler] Scheduled task: " + task.title() + " (" + task.schedule().type() + ")");
    }

    public void cancel(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            System.err.println("[Scheduler] Cancelled task: " + taskId);
        }
    }

    public void scheduleAll() {
        for (Task task : taskManager.getTasksByStatus(TaskStatus.TODO)) {
            if (task.schedule() != null && task.schedule().type() != TaskSchedule.ScheduleType.ONCE) {
                schedule(task);
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void executeTask(Task task) {
        System.err.println("[Scheduler] Executing: " + task.title());
        taskManager.updateStatus(task.id(), TaskStatus.IN_PROGRESS);
        try {
            taskManager.updateStatus(task.id(), TaskStatus.COMPLETED);
            System.err.println("[Scheduler] Completed: " + task.title());
        } catch (Exception e) {
            System.err.println("[Scheduler] Failed: " + task.title() + " - " + e.getMessage());
            taskManager.updateStatus(task.id(), TaskStatus.TODO);
        }
    }

    private long parseCronInterval(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return 3600;
        }
        String trimmed = cronExpression.trim();
        if (trimmed.startsWith("*/")) {
            try {
                return Long.parseLong(trimmed.substring(2));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return switch (trimmed) {
            case "@hourly", "0 * * * *" -> 3600;
            case "@daily", "0 0 * * *" -> 86400;
            case "@weekly", "0 0 * * 0" -> 604800;
            default -> 3600;
        };
    }
}
