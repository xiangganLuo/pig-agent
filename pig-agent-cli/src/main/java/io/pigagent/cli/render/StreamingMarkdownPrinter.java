package io.pigagent.cli.render;

import java.util.function.Consumer;

/**
 * Stateful, line-oriented streaming wrapper around {@link MarkdownAnsiRenderer}.
 *
 * <p>Answer text arrives from the agent stream in arbitrary chunks. This buffers a
 * partial line and flushes each complete line (rendered to ANSI) as soon as its
 * trailing newline arrives, so the user sees the answer build up incrementally
 * rather than all-at-once. Fenced-code state is tracked across lines. Call
 * {@link #flush} once the stream completes to emit any trailing partial line.
 *
 * <p>The rendered lines are handed to a {@link Consumer} sink (the terminal writer
 * in production, a list in tests) so the printer stays free of terminal deps.
 */
public final class StreamingMarkdownPrinter {

    private final StringBuilder buffer = new StringBuilder();
    private boolean insideCodeBlock = false;
    private boolean anyOutput = false;

    /** Append a chunk; emit every complete line it completes. */
    public void accept(String chunk, Consumer<String> lineSink) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer.append(chunk);
        int nl;
        while ((nl = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, nl);
            buffer.delete(0, nl + 1);
            emit(line, lineSink);
        }
    }

    /** Emit any buffered partial line (call on stream completion). */
    public void flush(Consumer<String> lineSink) {
        if (buffer.length() > 0) {
            emit(buffer.toString(), lineSink);
            buffer.setLength(0);
        }
    }

    /** Whether at least one line has been emitted. */
    public boolean hasOutput() {
        return anyOutput;
    }

    private void emit(String line, Consumer<String> lineSink) {
        if (MarkdownAnsiRenderer.isFence(line)) {
            lineSink.accept(MarkdownAnsiRenderer.renderLine(line, false));
            insideCodeBlock = !insideCodeBlock;
        } else {
            lineSink.accept(MarkdownAnsiRenderer.renderLine(line, insideCodeBlock));
        }
        anyOutput = true;
    }
}
