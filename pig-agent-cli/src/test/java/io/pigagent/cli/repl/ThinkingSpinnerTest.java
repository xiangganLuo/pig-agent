package io.pigagent.cli.repl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The animated thinking indicator drives repaints off an injected scheduler + clock, so its state
 * machine (start/tick/stop, elapsed formatting, erase, TTY degradation) is unit-tested without a real
 * terminal or real threads — mirroring how the render tests avoid a live terminal.
 */
class ThinkingSpinnerTest {

    @BeforeAll
    static void enableAnsi() {
        org.fusesource.jansi.Ansi.setEnabled(true);
    }

    private static final long SEC = 1_000_000_000L;

    @SuppressWarnings("rawtypes")
    private static ScheduledFuture future() {
        return mock(ScheduledFuture.class);
    }

    @Test
    void start_animated_schedulesRepaintsAndPaintsInitialFrame() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future());
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, () -> 0L, sink::add, true);
        spinner.start();

        verify(scheduler).scheduleAtFixedRate(any(),
                eq(ThinkingSpinner.REPAINT_INTERVAL_MS), eq(ThinkingSpinner.REPAINT_INTERVAL_MS),
                eq(TimeUnit.MILLISECONDS));
        assertThat(sink).hasSize(1);
        assertThat(sink.get(0)).contains("⠋").contains("思考中").contains("(0s)").contains("\r");
    }

    @Test
    void tick_advancesGlyphAndElapsedSeconds() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future());
        AtomicLong now = new AtomicLong(0);
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, now::get, sink::add, true);
        spinner.start();

        ArgumentCaptor<Runnable> repaint = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(repaint.capture(), anyLong(), anyLong(), any());

        now.set(3 * SEC);
        repaint.getValue().run(); // simulate one repaint tick

        assertThat(sink).hasSize(2);
        assertThat(sink.get(1)).contains("⠙").contains("(3s)");
    }

    @Test
    void stop_cancelsTaskAndErasesLine() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("rawtypes")
        ScheduledFuture task = future();
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(task);
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, () -> 0L, sink::add, true);
        spinner.start();
        spinner.stop();

        verify(task).cancel(false);
        assertThat(sink.get(sink.size() - 1)).contains("[2K"); // ANSI erase-line on stop
    }

    @Test
    void nonTty_noAnimation_staticLineOnly() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, () -> 0L, sink::add, false);
        spinner.start();

        verifyNoInteractions(scheduler); // never schedules → no \r animation spam on a dumb terminal
        assertThat(sink).hasSize(1);
        assertThat(sink.get(0)).contains("思考中");

        spinner.stop();
        assertThat(sink).hasSize(2); // static line erased, still no scheduler use
        verifyNoInteractions(scheduler);
    }

    @Test
    void start_isIdempotent() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future());
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, () -> 0L, sink::add, true);
        spinner.start();
        spinner.start(); // second start is a no-op

        verify(scheduler, times(1)).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        assertThat(sink).hasSize(1);
    }

    @Test
    void supportsMultipleCyclesWithinOneTurn() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future());
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, () -> 0L, sink::add, true);
        spinner.start();
        spinner.stop();
        spinner.start(); // re-show after a tool call / next reasoning step

        verify(scheduler, times(2)).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    void stop_withoutStart_isNoOp() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, () -> 0L, sink::add, true);
        spinner.stop();

        assertThat(sink).isEmpty();
        verify(scheduler, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    void frameContent_formatsGlyphLabelAndElapsed() {
        assertThat(ThinkingSpinner.frameContent("⠹", 12)).isEqualTo("⠹ 思考中… (12s)");
    }

    @Test
    void tick_afterLongWait_stillShowsThinkingNotRetry() {
        // A long wait with no data = the model is thinking/generating, NOT retrying — the label must
        // stay 思考中 (with the elapsed counter), never a fabricated "重试中".
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future());
        AtomicLong now = new AtomicLong(0);
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, now::get, sink::add, true);
        spinner.start(); // reasoning phase

        ArgumentCaptor<Runnable> repaint = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(repaint.capture(), anyLong(), anyLong(), any());

        now.set(90 * SEC);
        repaint.getValue().run();

        assertThat(sink.get(sink.size() - 1)).contains("思考中").contains("(90s)").doesNotContain("重试");
    }

    @Test
    void toolPhaseStart_showsExplicitLabel() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any())).thenReturn(future());
        AtomicLong now = new AtomicLong(0);
        List<String> sink = new ArrayList<>();

        ThinkingSpinner spinner = new ThinkingSpinner(scheduler, now::get, sink::add, true);
        spinner.start("运行中…"); // tool-execution phase → explicit label, still no retry wording

        ArgumentCaptor<Runnable> repaint = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(repaint.capture(), anyLong(), anyLong(), any());

        now.set(30 * SEC);
        repaint.getValue().run();

        assertThat(sink.get(sink.size() - 1)).contains("运行中").doesNotContain("重试");
    }
}
