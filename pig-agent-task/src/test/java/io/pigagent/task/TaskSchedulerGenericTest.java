package io.pigagent.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSchedulerGenericTest {

    @Test
    void genericSchedule_delayed_runsRunnable() throws InterruptedException {
        TaskScheduler scheduler = new TaskScheduler(null); // taskManager unused by the generic path
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("x", TaskSchedule.delayed(0), latch::countDown);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        scheduler.shutdown();
    }

    @Test
    void cronDelaySeconds_realCron_isPositiveWithinADay() {
        Long delay = TaskScheduler.cronDelaySeconds("0 2 * * *"); // daily 02:00
        assertThat(delay).isNotNull();
        assertThat(delay).isBetween(1L, 86400L);
    }

    @Test
    void cronDelaySeconds_nonStandard_returnsNull() {
        assertThat(TaskScheduler.cronDelaySeconds("*/30")).isNull(); // bare, not 5-field cron
        assertThat(TaskScheduler.cronDelaySeconds("garbage")).isNull();
        assertThat(TaskScheduler.cronDelaySeconds(null)).isNull();
    }

    @Test
    void legacyCronInterval_fallbacks() {
        assertThat(TaskScheduler.legacyCronInterval("*/45")).isEqualTo(45);
        assertThat(TaskScheduler.legacyCronInterval("@hourly")).isEqualTo(3600);
        assertThat(TaskScheduler.legacyCronInterval("@daily")).isEqualTo(86400);
    }

    @Test
    void cronSchedule_isRegistered_thenCancellable() {
        TaskScheduler scheduler = new TaskScheduler(null);

        scheduler.schedule("nightly", TaskSchedule.cron("0 2 * * *"), () -> { });
        assertThat(scheduler.isScheduled("nightly")).isTrue();

        scheduler.cancel("nightly");
        assertThat(scheduler.isScheduled("nightly")).isFalse();
        scheduler.shutdown();
    }
}
