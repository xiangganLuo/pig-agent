package io.pigagent.cli;

import org.fusesource.jansi.Ansi.Color;
import org.jline.terminal.Terminal;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Small helper around Jansi that produces colored ANSI strings and writes them
 * through a JLine {@link Terminal}.
 *
 * <p>Jansi is used purely as an ANSI <em>string builder</em> here — we never call
 * {@code AnsiConsole.systemInstall()}. JLine already owns the terminal (jna provider),
 * so installing Jansi's native hooks would double-wrap {@code System.out} and garble
 * output on Windows. JLine renders the ANSI escapes we emit on every platform.
 */
public final class Ansi {

    private Ansi() {
    }

    /** Cyan, used for prompts. */
    public static String prompt(String text) {
        return ansi().fg(Color.CYAN).a(text).reset().toString();
    }

    /** Default-colored informational text. */
    public static String info(String text) {
        return ansi().a(text).reset().toString();
    }

    /** Green, used for success / confirmations. */
    public static String success(String text) {
        return ansi().fgBright(Color.GREEN).a(text).reset().toString();
    }

    /** Yellow, used for warnings. */
    public static String warn(String text) {
        return ansi().fg(Color.YELLOW).a(text).reset().toString();
    }

    /** Red, used for errors. */
    public static String error(String text) {
        return ansi().fgBright(Color.RED).a(text).reset().toString();
    }

    /** Faint/dim text, used for thinking and tool traces. */
    public static String dim(String text) {
        return ansi().fgBright(Color.BLACK).a(text).reset().toString();
    }

    /** Bold magenta, used for headings and the banner. */
    public static String heading(String text) {
        return ansi().bold().fg(Color.MAGENTA).a(text).reset().toString();
    }

    /** Bold of the given color (used for table markers). */
    public static String bold(String text, Color color) {
        return ansi().bold().fg(color).a(text).reset().toString();
    }

    /** Write a line (already colored or plain) through the terminal and flush. */
    public static void println(Terminal terminal, String line) {
        terminal.writer().println(line);
        terminal.flush();
    }

    /** Write text without a trailing newline through the terminal and flush. */
    public static void print(Terminal terminal, String text) {
        terminal.writer().print(text);
        terminal.flush();
    }
}
