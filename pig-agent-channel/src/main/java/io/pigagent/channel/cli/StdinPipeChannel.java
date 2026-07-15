package io.pigagent.channel.cli;

import io.pigagent.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * A CLI / stdin pipe channel: reads inbound messages line-by-line from an input stream
 * (default {@code System.in}) and writes agent replies to an output stream (default
 * {@code System.out}). Blank lines are skipped. Useful for piping (`echo "hi" | pig-agent`) or
 * scripting, and fully self-contained (no external transport).
 *
 * <p>The read loop {@link #pump()} is package-private and synchronous so it can be unit-tested by
 * feeding a finite stream to EOF; {@link #start} runs it on a daemon thread.
 */
public final class StdinPipeChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(StdinPipeChannel.class);

    private final BufferedReader reader;
    private final PrintStream out;
    private volatile boolean running;
    private Thread thread;
    private Consumer<String> handler;

    public StdinPipeChannel() {
        this(System.in, System.out);
    }

    public StdinPipeChannel(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    @Override
    public String channelId() {
        return "stdin";
    }

    @Override
    public String displayName() {
        return "CLI Stdin Pipe";
    }

    @Override
    public void start(Consumer<String> messageHandler) {
        bind(messageHandler);
        thread = new Thread(this::pump, "stdin-channel");
        thread.setDaemon(true);
        thread.start();
        log.info("Stdin pipe channel started");
    }

    /** Wire the handler and mark running without starting the read thread (used by tests). */
    void bind(Consumer<String> messageHandler) {
        this.handler = messageHandler;
        this.running = true;
    }

    /** Read lines until stopped or EOF; each non-blank line is dispatched as an inbound message. */
    void pump() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.isBlank() && handler != null) {
                    handler.accept(line);
                }
            }
        } catch (IOException e) {
            log.warn("Stdin pipe channel read failed: {}", e.getMessage());
        }
    }

    @Override
    public void sendMessage(String message) {
        out.println(message);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
