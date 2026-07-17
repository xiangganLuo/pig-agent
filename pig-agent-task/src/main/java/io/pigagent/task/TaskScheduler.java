package io.pigagent.task;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final TaskManager taskManager;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Optional real-work seam (nullable). When set, {@link #executeTask(Task)} delegates the actual
     * work of a fired {@link Task} to it (e.g. dispatch the task to an agent). When {@code null}
     * (the default), a scheduled task is a reminder/marker only — it flips its status through the
     * lifecycle without performing any work. Wiring a real executor is a caller responsibility
     * (e.g. {@code AgentBootstrap}); see {@link #setTaskExecutor(Consumer)}.
     */
    private volatile Consumer<Task> taskExecutor;

    public TaskScheduler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * Wire the optional task executor that does the real work when a scheduled {@link Task} fires.
     * Pass {@code null} to clear it (back to reminder-only markers). Idempotent and thread-safe.
     *
     * <p>Contract: the executor is invoked on a scheduler thread with the fired task; if it throws,
     * the task is marked {@link TaskStatus#TODO} again (so it can be retried), otherwise it is marked
     * {@link TaskStatus#COMPLETED}. Keep the work bounded — a long-running executor holds a pool thread.
     */
    public void setTaskExecutor(Consumer<Task> taskExecutor) {
        this.taskExecutor = taskExecutor;
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
        log.info("Scheduled task: {} ({})", task.title(), task.schedule().type());
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
                log.error("Run failed for '{}': {}", id, e.getMessage(), e);
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
        Consumer<Task> exec = this.taskExecutor;
        if (exec == null) {
            // No executor wired → this scheduled task is a reminder/marker only, NOT an autonomous
            // run: it performs no work. Kept honest here (vs the digital-employee path, which really
            // invokes the agent). Status still flips TODO→IN_PROGRESS→COMPLETED (behavior-neutral).
            log.info("Task fired (reminder-only marker, no executor wired): {}", task.title());
            taskManager.updateStatus(task.id(), TaskStatus.IN_PROGRESS);
            taskManager.updateStatus(task.id(), TaskStatus.COMPLETED);
            return;
        }
        log.info("Executing task via executor: {}", task.title());
        taskManager.updateStatus(task.id(), TaskStatus.IN_PROGRESS);
        try {
            exec.accept(task);
            taskManager.updateStatus(task.id(), TaskStatus.COMPLETED);
            log.info("Completed task: {}", task.title());
        } catch (Exception e) {
            log.error("Task failed: {} - {}", task.title(), e.getMessage(), e);
            taskManager.updateStatus(task.id(), TaskStatus.TODO);
        }
    }

    /**
     * True if {@code expr} is a valid standard 5-field (UNIX) cron expression (e.g. {@code
     * "0 2 * * *"}). Used to validate a schedule before it is accepted (the legacy every-N-seconds /
     * {@code @macro} fallbacks are intentionally NOT treated as valid here — they map to a coarse
     * fixed interval and should not be offered as a real cron schedule).
     */
    public static boolean isValidCron(String expr) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        try {
            CronParser parser = new CronParser(
                    CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
            parser.parse(expr.trim()).validate();
            return true;
        } catch (Exception e) {
            return false;
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
