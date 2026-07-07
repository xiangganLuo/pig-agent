package io.pigagent.core.interrupt;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch + concurrency coverage for {@link InterruptController} (task 3.3). */
class InterruptControllerTest {

    @Test
    void interruptCurrent_whenIdle_isNoOp() {
        assertThat(new InterruptController().interruptCurrent()).isFalse();
    }

    @Test
    void begin_makesTurnCurrent_interruptFires_endClears() {
        InterruptController controller = new InterruptController();

        TurnHandle handle = controller.begin();
        assertThat(controller.currentTurn()).contains(handle);
        assertThat(controller.interruptCurrent()).isTrue();

        controller.end(handle);
        assertThat(controller.currentTurn()).isEmpty();
        assertThat(controller.interruptCurrent()).isFalse();
    }

    @Test
    void end_staleHandle_doesNotClobberNewerTurn() {
        InterruptController controller = new InterruptController();

        TurnHandle first = controller.begin();
        TurnHandle second = controller.begin(); // replaces first as current
        controller.end(first);                  // stale end MUST NOT clear the newer turn

        assertThat(controller.currentTurn()).contains(second);
        assertThat(controller.interruptCurrent()).isTrue();
    }

    @Test
    void begin_assignsMonotonicTurnIds() {
        InterruptController controller = new InterruptController();
        long a = controller.begin().turnId();
        long b = controller.begin().turnId();
        assertThat(b).isGreaterThan(a);
    }

    @Test
    void concurrentBeginEnd_isRaceSafe() throws Exception {
        InterruptController controller = new InterruptController();
        int threads = 8;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < iterations; n++) {
                            TurnHandle h = controller.begin();
                            controller.interruptCurrent(); // never throws
                            controller.end(h);
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors.get()).isZero();
        // After all turns end, the controller is idle (last writer's end may or may not win, but no
        // exception occurred and interruptCurrent stays a safe no-op / true without throwing).
        controller.interruptCurrent();
    }
}
