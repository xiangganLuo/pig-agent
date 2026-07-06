package io.pigagent.task;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes scheduled work on a background thread pool. Supports DELAYED (delayed one-shot) and
 * CRON (recurring) schedules; ONCE is not scheduled.
 *
 * <p>Generalized beyond tasks: {@link #schedule(String, TaskSchedule, Runnable)} schedules any
 * {@link Runnable} (used by the digital-employee runner), while {@link #schedule(Task)} keeps the
 * task lifecycle. CRON supports real 5-field expressions ("0 2 * * *" = daily at 02:00) via
 * cron-utils with self-rescheduling to the next execution; the legacy every-N-seconds and
 * {@code @macro} forms fall back to a fixed interval.
 */
public final class TaskScheduler {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final TaskManager taskManager;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public TaskScheduler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /** Schedule any runnable under an id. ONCE/null is ignored. Re-scheduling an id cancels first. */
    public void schedule(String id, TaskSchedule schedule, Runnable action) {
        if (schedule == null || schedule.type() == TaskSchedule.ScheduleType.ONCE) {
            return;
        }
        cancel(id);
        switch (schedule.type()) {
            case DELAYED -> {
                long seconds = schedule.delaySeconds() != null ? schedule.delaySeconds() : 60;
                scheduledTasks.put(id, executor.schedule(action, seconds, TimeUnit.SECONDS));
            }
            case CRON -> {
                Long delay = cronDelaySeconds(schedule.cronExpression());
                if (delay != null) {
                    scheduleCronNext(id, schedule.cronExpression(), action); // real cron, self-reschedule
                } else {
                    long interval = legacyCronInterval(schedule.cronExpression());
                    scheduledTasks.put(id, executor.scheduleAtFixedRate(
                            action, interval, interval, TimeUnit.SECONDS));
                }
            }
            default -> { /* ONCE handled above */ }
        }
    }

    public void schedule(Task task) {
        if (task.schedule() == null || task.schedule().type() == TaskSchedule.ScheduleType.ONCE) {
            return;
        }
        schedule(task.id(), task.schedule(), () -> executeTask(task));
        System.err.println("[Scheduler] Scheduled task: " + task.title() + " (" + task.schedule().type() + ")");
    }

    private void scheduleCronNext(String id, String cronExpr, Runnable action) {
        Long delay = cronDelaySeconds(cronExpr);
        if (delay == null) {
            return;
        }
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                action.run();
            } catch (Exception e) {
                System.err.println("[Scheduler] Run failed for '" + id + "': " + e.getMessage());
            } finally {
                if (scheduledTasks.containsKey(id)) {
                    scheduleCronNext(id, cronExpr, action); // reschedule to the following occurrence
                }
            }
        }, delay, TimeUnit.SECONDS);
        scheduledTasks.put(id, future);
    }

    public void cancel(String id) {
        ScheduledFuture<?> future = scheduledTasks.remove(id);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** True if an id currently has a live schedule (for tests / status). */
    public boolean isScheduled(String id) {
        return scheduledTasks.containsKey(id);
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

    /**
     * Seconds until the next execution of a standard 5-field cron expression (UNIX), or null if the
     * expression is not a valid 5-field cron (caller falls back to {@link #legacyCronInterval}).
     */
    static Long cronDelaySeconds(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }
        try {
            CronParser parser = new CronParser(
                    CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
            Cron cron = parser.parse(cronExpression.trim());
            cron.validate();
            return ExecutionTime.forCron(cron)
                    .timeToNextExecution(ZonedDateTime.now())
                    .map(d -> Math.max(1L, d.getSeconds()))
                    .orElse(null);
        } catch (Exception e) {
            return null; // not a standard cron → legacy interval path
        }
    }

    /** Legacy fixed-interval fallback: an every-N-seconds form (slash-N) and {@code @macro} forms. */
    static long legacyCronInterval(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return 3600;
        }
        String trimmed = cronExpression.trim();
        if (trimmed.startsWith("*/")) {
            try {
                return Long.parseLong(trimmed.substring(2));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return switch (trimmed) {
            case "@hourly" -> 3600;
            case "@daily" -> 86400;
            case "@weekly" -> 604800;
            default -> 3600;
        };
    }
}
