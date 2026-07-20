package io.pigagent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed task repository: one Markdown file per task under {@code tasks/{date}/{id}.md}.
 *
 * <p>Fault-tolerant like the JSON stores: a single corrupt/incomplete {@code .md} (missing field,
 * bad status enum, truncated content) is skipped with a warning rather than aborting
 * {@code findAll}/{@code findByStatus}/{@code findById} — so a bad file never crashes the startup
 * {@code scheduleAll}. Persistence is lossless: the schedule (incl. cron expression / delay
 * seconds), timestamps and description round-trip so CRON/DELAYED tasks survive a restart and are
 * re-scheduled correctly.
 */
public final class FileSystemTaskRepository implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSystemTaskRepository.class);

    /** Cap the stored run summary so one huge agent output can't bloat the task file. */
    private static final int MAX_RESULT_CHARS = 4000;

    private final Path tasksDir;

    public FileSystemTaskRepository(Path tasksDir) { this.tasksDir = tasksDir; }

    @Override
    public Task save(Task task) {
        try {
            Path dateDir = tasksDir.resolve(LocalDate.now().toString());
            Files.createDirectories(dateDir);
            Files.writeString(dateDir.resolve(task.id() + ".md"), toMarkdown(task));
            return task;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task: " + task.id(), e);
        }
    }

    @Override
    public Optional<Task> findById(String id) {
        try (Stream<Path> walk = Files.walk(tasksDir)) {
            return walk.filter(p -> p.getFileName().toString().equals(id + ".md"))
                    .findFirst().map(this::parseMarkdown).filter(Objects::nonNull);
        } catch (IOException e) { return Optional.empty(); }
    }

    @Override
    public List<Task> findAll() {
        List<Task> tasks = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(tasksDir)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                    .map(this::parseMarkdown)
                    .filter(Objects::nonNull)
                    .forEach(tasks::add);
        } catch (IOException ignored) {}
        return tasks;
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return findAll().stream().filter(t -> t.status() == status).toList();
    }

    @Override
    public void deleteById(String id) {
        try (Stream<Path> walk = Files.walk(tasksDir)) {
            walk.filter(p -> p.getFileName().toString().equals(id + ".md")).findFirst()
                    .ifPresent(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private String toMarkdown(Task t) {
        // LastRun/Result are written BEFORE Updated so the description delimiter (keyed on the Updated
        // line) is unaffected — old files without these lines parse identically (fields → null).
        return ("# %s\n\n- **ID**: %s\n- **Status**: %s\n- **Schedule**: %s\n- **Created**: %s\n"
                + "- **LastRun**: %s\n- **Result**: %s\n- **Updated**: %s\n\n%s\n")
                .formatted(t.title(), t.id(), t.status(), encodeSchedule(t.schedule()),
                        t.createdAt(), encodeInstant(t.lastRunAt()), encodeResult(t.result()),
                        t.updatedAt(), t.description());
    }

    /**
     * Parse one task file. Any failure (IO, missing field, bad status enum, malformed content) is
     * logged and turned into a {@code null} so the caller can skip it — a single bad file must never
     * crash the listing or the startup scheduler.
     */
    private Task parseMarkdown(Path path) {
        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n");
            String title = lines.length > 0 && lines[0].startsWith("# ") ? lines[0].substring(2).trim() : "Untitled";
            String id = extractField(content, "ID");
            TaskStatus status = TaskStatus.valueOf(extractField(content, "Status"));
            TaskSchedule schedule = parseSchedule(extractField(content, "Schedule"));
            Instant created = parseInstant(extractField(content, "Created"));
            Instant lastRun = parseNullableInstant(extractField(content, "LastRun"));
            String result = decodeResult(extractField(content, "Result"));
            Instant updated = parseInstant(extractField(content, "Updated"));
            String description = extractDescription(content);
            return new Task(id != null ? id : path.getFileName().toString().replace(".md", ""),
                    title, description, status, schedule, created, updated, null, result, lastRun);
        } catch (Exception e) {
            log.warn("Skipping unreadable task file {}: {}", path.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Extract a single-line metadata field value. Matching is <b>anchored</b> to a line that (after
     * strip) starts with {@code - **<field>**:}, so a field's VALUE that happens to contain another
     * field's marker (e.g. an encoded Result mentioning {@code **Updated**:}) can never be mistaken for
     * that field. Returns {@code null} when the field is absent (old files without LastRun/Result).
     */
    private String extractField(String content, String field) {
        String prefix = "- **" + field + "**:";
        for (String line : content.split("\n")) {
            String s = line.strip();
            if (s.startsWith(prefix)) {
                return s.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** Reversible single-line encoding: {@code ONCE} / {@code CRON:<expr>} / {@code DELAYED:<seconds>}. */
    private String encodeSchedule(TaskSchedule schedule) {
        if (schedule == null) {
            return "ONCE";
        }
        return switch (schedule.type()) {
            case CRON -> "CRON:" + (schedule.cronExpression() == null ? "" : schedule.cronExpression());
            case DELAYED -> "DELAYED:" + (schedule.delaySeconds() == null ? "0" : schedule.delaySeconds());
            case ONCE -> "ONCE";
        };
    }

    /**
     * Rebuild a {@link TaskSchedule} from the encoded Schedule line. Tolerates the old format, where
     * only the type was written (e.g. {@code CRON} with no value): that degrades to {@code once()}
     * rather than throwing.
     */
    private TaskSchedule parseSchedule(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskSchedule.once();
        }
        String v = raw.trim();
        if (v.startsWith("CRON:")) {
            String expr = v.substring("CRON:".length()).trim();
            return expr.isEmpty() ? TaskSchedule.once() : TaskSchedule.cron(expr);
        }
        if (v.startsWith("DELAYED:")) {
            String num = v.substring("DELAYED:".length()).trim();
            try {
                return TaskSchedule.delayed(Long.parseLong(num));
            } catch (NumberFormatException e) {
                return TaskSchedule.once();
            }
        }
        // Old format (bare "ONCE"/"CRON"/"DELAYED" with no value) or ONCE → degrade to once().
        return TaskSchedule.once();
    }

    /** Parse an ISO-8601 instant, falling back to now on any failure (never throws). */
    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /** ISO-8601 encoding of an optional instant ({@code null} → empty, so old files read back null). */
    private String encodeInstant(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    /** Parse an optional ISO-8601 instant; blank/absent/unparseable → {@code null} (never throws). */
    private Instant parseNullableInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encode a run summary onto a single metadata line: capped at {@link #MAX_RESULT_CHARS}, backslashes
     * escaped, and every newline (CRLF/CR/LF) collapsed to a literal {@code \n} so the value stays on one
     * line. {@code null}/empty → empty string (round-trips back to {@code null}).
     */
    private static String encodeResult(String result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        String capped = result.length() > MAX_RESULT_CHARS
                ? result.substring(0, MAX_RESULT_CHARS) + "…" : result;
        return capped.replace("\\", "\\\\")
                .replace("\r\n", "\n").replace("\r", "\n")
                .replace("\n", "\\n");
    }

    /** Reverse {@link #encodeResult}: unescape {@code \n} → newline and {@code \\} → backslash. */
    private static String decodeResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == '\\') {
                    sb.append('\\');
                } else {
                    sb.append(c).append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The task body: everything after the metadata block (the blank line following {@code Updated}). */
    private String extractDescription(String content) {
        String[] lines = content.split("\n", -1);
        int updatedIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().startsWith("- **Updated**:")) {
                updatedIdx = i;
                break;
            }
        }
        if (updatedIdx < 0) {
            return "";
        }
        int i = updatedIdx + 1;
        while (i < lines.length && lines[i].isBlank()) {
            i++; // skip the blank line(s) separating metadata from the body
        }
        StringBuilder sb = new StringBuilder();
        for (; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        return sb.toString().stripTrailing();
    }
}
