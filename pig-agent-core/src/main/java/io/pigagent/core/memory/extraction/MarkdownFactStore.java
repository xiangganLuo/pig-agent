package io.pigagent.core.memory.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File-backed {@link FactStore} writing facts as Markdown that is <b>both human-readable and
 * machine-parseable</b>: each fact is a bullet whose visible text is the classified statement, with
 * the dedup/gating metadata tucked into a trailing HTML comment.
 *
 * <pre>{@code
 * # Extracted memory
 *
 * - [user-preference] Prefers responses in Chinese. <!-- subject=language; conf=0.95; correction=false -->
 * - [project-fact] Builds with Maven. <!-- subject=build-tool; conf=0.88; correction=false -->
 * }</pre>
 *
 * <p>This points at the same {@code temp-memory.md} the session's {@link
 * io.pigagent.core.memory.FileSystemLongTermMemory} reads for retrieval — so writes go through this
 * store and retrieval sees the structured facts. Legacy raw lines (from the pre-extraction record
 * format, or any line without the metadata comment) simply don't parse back on {@link #load()} and
 * are skipped (fault-tolerant); they remain in the file and are still visible to retrieval.
 */
public final class MarkdownFactStore implements FactStore {

    private static final Logger log = LoggerFactory.getLogger(MarkdownFactStore.class);
    private static final String HEADER = "# Extracted memory\n";

    private static final Pattern LINE = Pattern.compile(
            "^-\\s*\\[([^\\]]+)\\]\\s*(.*?)\\s*<!--\\s*subject=(.*?);\\s*conf=([0-9]*\\.?[0-9]+);"
                    + "\\s*correction=(true|false)\\s*-->\\s*$");

    private final Path file;

    public MarkdownFactStore(Path file) {
        this.file = file;
    }

    @Override
    public List<ExtractedFact> load() {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        try {
            List<ExtractedFact> facts = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                ExtractedFact fact = parseLine(line);
                if (fact != null) {
                    facts.add(fact);
                }
            }
            return facts;
        } catch (IOException e) {
            log.warn("Failed to read extracted-memory file: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void save(List<ExtractedFact> facts) {
        if (facts == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (ExtractedFact fact : facts) {
            if (fact == null || fact.isBlank()) {
                continue;
            }
            sb.append(render(fact)).append('\n');
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            log.warn("Failed to write extracted-memory file: {}", e.getMessage());
        }
    }

    private static String render(ExtractedFact fact) {
        return "- [" + fact.category().label() + "] " + oneLine(fact.statement())
                + " <!-- subject=" + oneLine(fact.subject())
                + "; conf=" + fact.confidence()
                + "; correction=" + fact.correction() + " -->";
    }

    private static ExtractedFact parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher m = LINE.matcher(line.strip());
        if (!m.matches()) {
            return null; // legacy raw line / not a fact — skip (fault-tolerant)
        }
        try {
            FactCategory category = FactCategory.fromLabel(m.group(1), FactCategory.PROJECT_FACT);
            String statement = m.group(2).strip();
            String subject = m.group(3).strip();
            double conf = Double.parseDouble(m.group(4));
            boolean correction = Boolean.parseBoolean(m.group(5));
            ExtractedFact fact = new ExtractedFact(subject, category, statement, conf, correction);
            return fact.isBlank() ? null : fact;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
