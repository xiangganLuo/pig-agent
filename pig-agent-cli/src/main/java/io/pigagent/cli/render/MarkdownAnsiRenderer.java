package io.pigagent.cli.render;

import org.fusesource.jansi.Ansi.Color;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.fusesource.jansi.Ansi.ansi;

/**
 * Pure Markdown → ANSI renderer for the CC-style REPL.
 *
 * <p>Deliberately small: it handles the subset that shows up in chat answers —
 * {@code **bold**}, {@code `inline code`}, fenced {@code ```} code blocks,
 * {@code -}/{@code *} list items and {@code #} headings. It is line-oriented so a
 * streaming printer can render answers as complete lines arrive (see
 * {@link StreamingMarkdownPrinter}).
 *
 * <p>Like {@link io.pigagent.cli.Ansi}, Jansi is used purely as an ANSI string
 * builder — no {@code AnsiConsole.systemInstall()}. Unclosed markers are left
 * literal rather than swallowing the rest of the line.
 */
public final class MarkdownAnsiRenderer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LIST = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");

    private MarkdownAnsiRenderer() {
    }

    /** True when the line opens or closes a fenced code block (```). */
    public static boolean isFence(String line) {
        return line != null && line.stripLeading().startsWith("```");
    }

    /**
     * Render a full multi-line Markdown block, tracking fenced-code state across
     * lines so markers inside a fence stay literal. Line count is preserved.
     */
    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String[] lines = markdown.split("\n", -1);
        boolean insideCode = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isFence(line)) {
                out.append(renderLine(line, false));
                insideCode = !insideCode;
            } else {
                out.append(renderLine(line, insideCode));
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Render a single line to ANSI. Inside a code block markers are literal and the
     * whole line is colored; fence lines are dimmed; otherwise headings, list items
     * and inline markers are styled.
     */
    public static String renderLine(String line, boolean insideCodeBlock) {
        if (line == null) {
            return "";
        }
        if (insideCodeBlock) {
            return ansi().fg(Color.GREEN).a(line).reset().toString();
        }
        if (isFence(line)) {
            return ansi().fgBright(Color.BLACK).a(line).reset().toString();
        }
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            return ansi().bold().fg(Color.MAGENTA).a(heading.group(2)).reset().toString();
        }
        Matcher list = LIST.matcher(line);
        if (list.matches()) {
            return list.group(1) + "• " + renderInline(list.group(2));
        }
        return renderInline(line);
    }

    /** Render {@code **bold**} and {@code `code`} spans; leave unclosed markers literal. */
    static String renderInline(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i)) {
                int close = text.indexOf("**", i + 2);
                if (close > i + 1) {
                    String inner = text.substring(i + 2, close);
                    out.append(ansi().bold().a(inner).reset().toString());
                    i = close + 2;
                    continue;
                }
            }
            char c = text.charAt(i);
            if (c == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i) {
                    String inner = text.substring(i + 1, close);
                    out.append(ansi().fg(Color.CYAN).a(inner).reset().toString());
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
