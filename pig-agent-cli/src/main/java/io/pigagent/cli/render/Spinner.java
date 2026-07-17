package io.pigagent.cli.render;

/**
 * A pure, timing-free braille spinner: {@link #glyph(int)} maps a monotonically increasing tick to
 * the current frame, wrapping around the frame set. Claude-Code-style braille frames
 * ({@code ⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏}). No terminal, no clock, no threads — the "what spins" half of the animated
 * thinking indicator, kept separate from {@code ThinkingSpinner} (the "when/how it spins" half) so it
 * is trivially unit-testable.
 */
public final class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /** The frame for the given tick, wrapping around (negative ticks are floor-modded). */
    public String glyph(int tick) {
        return FRAMES[Math.floorMod(tick, FRAMES.length)];
    }

    /** Number of distinct frames in the cycle. */
    public int frameCount() {
        return FRAMES.length;
    }
}
