package io.pigagent.core.memory.extraction;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Debounce coalescing + flush + flush-on-close + throwing-job isolation. */
class AsyncMemoryExtractionSchedulerTest {

    @Test
    void submitDoesNotRunSynchronously_thenFlushCoalescesRapidSubmits() {
        AtomicInteger runs = new AtomicInteger();
        // Long debounce so the timer never fires during the test — only flush() runs it.
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            scheduler.submit(runs::incrementAndGet);
            scheduler.submit(runs::incrementAndGet);
            scheduler.submit(runs::incrementAndGet);

            // Off the critical path: submitting scheduled work, nothing has executed yet.
            assertThat(runs.get()).isZero();

            scheduler.flush();

            // Three rapid submits coalesced to a single execution.
            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Test
    void timerFiresAfterDebounceQuietPeriod() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(30)) {
            scheduler.submit(latch::countDown);
            assertThat(latch.await(3, TimeUnit.SECONDS)).as("debounced job fired").isTrue();
        }
    }

    @Test
    void closeFlushesPendingWork() {
        AtomicInteger runs = new AtomicInteger();
        AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000);
        scheduler.submit(runs::incrementAndGet);

        scheduler.close(); // must flush the still-pending job before shutting the thread down

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void throwingJobIsIsolated_schedulerStaysUsable() {
        AtomicInteger runs = new AtomicInteger();
        try (AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(60_000)) {
            scheduler.submit(() -> {
                throw new RuntimeException("boom");
            });
            assertThatCode(scheduler::flush).doesNotThrowAnyException();

            scheduler.submit(runs::incrementAndGet);
            scheduler.flush();
            assertThat(runs.get()).isEqualTo(1);
        }
    }

    @Test
    void submitAfterCloseIsIgnored() {
        AtomicInteger runs = new AtomicInteger();
        AsyncMemoryExtractionScheduler scheduler = new AsyncMemoryExtractionScheduler(10);
        scheduler.close();
        scheduler.submit(runs::incrementAndGet);
        scheduler.flush();
        assertThat(runs.get()).isZero();
    }
}
