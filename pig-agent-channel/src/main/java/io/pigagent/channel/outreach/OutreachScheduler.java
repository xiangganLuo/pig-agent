package io.pigagent.channel.outreach;

/**
 * A framework-neutral scheduling seam for proactive outreach: register a cron-driven action under an
 * id. The CLI adapts this to the existing {@code TaskScheduler.schedule(id, TaskSchedule.cron(expr),
 * action)}, so the outreach layer never depends on the task module directly and stays unit-testable
 * with a fake scheduler.
 */
@FunctionalInterface
public interface OutreachScheduler {

    /** Schedule {@code action} to run on the given cron expression under {@code id}. */
    void schedule(String id, String cron, Runnable action);
}
