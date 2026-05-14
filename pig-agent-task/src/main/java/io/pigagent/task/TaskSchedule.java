package io.pigagent.task;

public record TaskSchedule(ScheduleType type, String cronExpression, Long delaySeconds) {
    public enum ScheduleType { ONCE, CRON, DELAYED }
    public static TaskSchedule once() { return new TaskSchedule(ScheduleType.ONCE, null, null); }
    public static TaskSchedule cron(String expr) { return new TaskSchedule(ScheduleType.CRON, expr, null); }
    public static TaskSchedule delayed(long seconds) { return new TaskSchedule(ScheduleType.DELAYED, null, seconds); }
}
