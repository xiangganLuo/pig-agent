package io.pigagent.cli.repl.select;

/**
 * Pure cursor state for an inline list selector: a highlighted index over a fixed number of
 * options, clamped at both ends (no wrap). Kept separate from terminal I/O so the navigation
 * logic is unit-testable without a real terminal.
 */
public final class SelectorModel {

    private final int size;
    private int cursor;

    public SelectorModel(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        this.size = size;
    }

    public int cursor() {
        return cursor;
    }

    public int size() {
        return size;
    }

    /** Move the highlight up one, clamped at the top. */
    public void up() {
        if (cursor > 0) {
            cursor--;
        }
    }

    /** Move the highlight down one, clamped at the bottom. */
    public void down() {
        if (cursor < size - 1) {
            cursor++;
        }
    }
}
