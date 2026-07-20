package io.pigagent.cli.repl;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.render.Spinner;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The REPL's animated "thinking" indicator — a small state machine that, while the model reasons
 * (before the first answer text / tool call), paints a spinning braille glyph + {@code thinking…} +
 * elapsed seconds on its own line (Claude-Code style), then stops and clears the line when real
 * output begins.
 *
 * <p><b>Driven by an injected scheduler + clock</b> (never {@code new}s its own), so the whole
 * state machine — start/tick/stop, elapsed formatting, erase, TTY degradation — is unit-testable
 * without a real terminal or real threads. In production {@code AgentRepl} shares one single-daemon
 * {@link ScheduledExecutorService} across turns and passes a sink that writes to the JLine terminal.
 *
 * <p><b>No interleaving:</b> {@code start()}, the repaint tick, and {@code stop()} all run under one
 * lock, so a repaint's write and a {@code stop()} erase are mutually exclusive; once {@code stop()}
 * returns (task cancelled, line erased, {@code active=false}) no further tick writes. The render loop
 * calls {@code stop()} before printing any answer/tool line, so the spinner line is always cleared
 * before content prints.
 *
 * <p><b>Graceful degradation:</b> when {@code animated} is false (non-TTY / {@code repl.spinner}
 * off), {@code start()} never touches the scheduler — it prints a single static line and
 * {@code stop()} erases it — so a dumb/redirected terminal gets no {@code \r} animation spam.
 *
 * <p><b>Idempotent + cyclic:</b> {@code start()}/{@code stop()} may alternate any number of times
 * within one turn (multi-step ReAct reasoning); each {@code start()} restarts the glyph + timer.
 */
public final class ThinkingSpinner {

    /** Repaint period (ms) — within the ~80–120ms Claude-Code-style range. */
    public static final long REPAINT_INTERVAL_MS = 90L;

    private static final String LABEL = "思考中…";
    private static final String STATIC_LINE = "⋯ 思考中";
    /** ANSI: carriage-return + erase entire line (rendered by JLine on all platforms). */
    private static final String ERASE_ANSI = "\r[2K";
    /** Static-degradation erase: CR + spaces + CR (matches the pre-animation behavior). */
    private static final String ERASE_STATIC = "\r" + " ".repeat(STATIC_LINE.length() + 2) + "\r";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Spinner spinner = new Spinner();
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoClock;
    private final Consumer<String> sink;
    private final boolean animated;

    private final Object lock = new Object();
    private boolean active;            // guarded by lock: is the indicator currently shown
    private ScheduledFuture<?> task;   // guarded by lock
    private long startNanos;           // guarded by lock
    private int tick;                  // guarded by lock
    private String baseLabel = LABEL;  // guarded by lock: label for the current start() cycle

    public ThinkingSpinner(ScheduledExecutorService scheduler, LongSupplier nanoClock,
                           Consumer<String> sink, boolean animated) {
        this.scheduler = scheduler;
        this.nanoClock = nanoClock;
        this.sink = sink;
        this.animated = animated;
    }

    /** Show the reasoning indicator: {@code 思考中… (Ns)}. */
    public void start() {
        start(LABEL);
    }

    /**
     * Show the indicator with an explicit base label (a tool-execution phase passes {@code 运行中…}).
     * The label is shown verbatim with an elapsed-seconds counter and does <b>not</b> flip to a
     * "retrying" wording: a long wait with no data is the model thinking / generating (or a slow first
     * token), not a retry — and the native model path exposes no per-retry signal — so we never
     * fabricate a "重试中" label that misleads. Paint the first frame + (on a TTY) start the repaint
     * timer. Idempotent.
     */
    public void start(String baseLabel) {
        synchronized (lock) {
            if (active) {
                return;
            }
            active = true;
            this.baseLabel = (baseLabel == null || baseLabel.isBlank()) ? LABEL : baseLabel;
            startNanos = nanoClock.getAsLong();
            tick = 0;
            if (!animated) {
                sink.accept("\r" + Ansi.dim(STATIC_LINE)); // non-TTY: one static line, no timer
                return;
            }
            paintLocked();
            task = scheduler.scheduleAtFixedRate(
                    this::onTick, REPAINT_INTERVAL_MS, REPAINT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Stop the indicator: cancel the timer and clear the line. Idempotent (no-op if not shown). */
    public void stop() {
        synchronized (lock) {
            if (!active) {
                return;
            }
            active = false;
            if (task != null) {
                task.cancel(false);
                task = null;
            }
            sink.accept(animated ? ERASE_ANSI : ERASE_STATIC);
        }
    }

    private void onTick() {
        synchronized (lock) {
            if (!active) {
                return;
            }
            tick++;
            paintLocked();
        }
    }

    /** Paint the current frame in place. Caller holds {@link #lock}. */
    private void paintLocked() {
        long elapsedSeconds = (nanoClock.getAsLong() - startNanos) / NANOS_PER_SECOND;
        sink.accept("\r" + Ansi.dim(frameContent(spinner.glyph(tick), elapsedSeconds, baseLabel)));
    }

    /** The unstyled line content (e.g. {@code ⠹ 思考中… (3s)}); pure, for deterministic testing. */
    static String frameContent(String glyph, long elapsedSeconds) {
        return frameContent(glyph, elapsedSeconds, LABEL);
    }

    /** As {@link #frameContent(String, long)} with an explicit label. Pure. */
    static String frameContent(String glyph, long elapsedSeconds, String label) {
        return glyph + " " + label + " (" + elapsedSeconds + "s)";
    }
}
