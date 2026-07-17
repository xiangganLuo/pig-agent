package io.pigagent.core.memory.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the memory corpus — the consolidated {@code MEMORY.md} + the daily ledger {@code memory/*.md}
 * + the user profile {@code USER.md} — into chunked {@link MemoryDocument}s for indexing (capability
 * {@code hybrid-memory-search}). <b>Fault-tolerant</b>: a missing/unreadable file or directory is
 * skipped (never throws), so a partial corpus still indexes. Chunking splits each file on Markdown
 * headings and paragraph breaks, capped at a max chunk size, so a search hit is a focused snippet.
 */
public final class MemoryCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(MemoryCorpusLoader.class);

    /** Max characters per chunk before a hard split (keeps hits focused). */
    static final int MAX_CHUNK_CHARS = 800;

    private final Path memoryFile; // MEMORY.md (nullable)
    private final Path memoryDir;  // memory/  (nullable)
    private final Path userFile;   // USER.md  (nullable)

    public MemoryCorpusLoader(Path memoryFile, Path memoryDir, Path userFile) {
        this.memoryFile = memoryFile;
        this.memoryDir = memoryDir;
        this.userFile = userFile;
    }

    /**
     * A cheap change signal for incremental rebuild: combines the current modification times of every
     * corpus file (and the daily-ledger dir listing). A change to any file changes the fingerprint.
     */
    public long fingerprint() {
        long fp = 1L;
        fp = 31 * fp + mtime(memoryFile);
        fp = 31 * fp + mtime(userFile);
        for (Path p : ledgerFiles()) {
            fp = 31 * fp + mtime(p);
        }
        return fp;
    }

    /** Load the whole corpus as chunked documents (empty list when nothing is readable). */
    public List<MemoryDocument> load() {
        List<MemoryDocument> docs = new ArrayList<>();
        appendFile(docs, memoryFile, "MEMORY.md");
        for (Path p : ledgerFiles()) {
            appendFile(docs, p, "memory/" + p.getFileName());
        }
        appendFile(docs, userFile, "USER.md");
        return docs;
    }

    private void appendFile(List<MemoryDocument> out, Path file, String label) {
        String content = read(file);
        if (content == null || content.isBlank()) {
            return;
        }
        int ordinal = 0;
        for (String chunk : chunk(content)) {
            out.add(new MemoryDocument(label + "#" + ordinal++, label, chunk));
        }
    }

    /** The daily-ledger {@code memory/*.md} files, sorted by name; empty when the dir is absent. */
    private List<Path> ledgerFiles() {
        List<Path> files = new ArrayList<>();
        if (memoryDir == null || !Files.isDirectory(memoryDir)) {
            return files;
        }
        try (var stream = Files.list(memoryDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not list memory ledger dir {}: {}", memoryDir, e.getMessage());
        }
        return files;
    }

    private static String read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read corpus file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static long mtime(Path file) {
        if (file == null) {
            return 0L;
        }
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    /**
     * Split {@code content} into chunks: start a new chunk at each Markdown heading ({@code #...}) and
     * whenever the current chunk would exceed {@link #MAX_CHUNK_CHARS}. Blank-only chunks are dropped.
     */
    static List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            boolean heading = line.startsWith("#");
            if ((heading || cur.length() + line.length() + 1 > MAX_CHUNK_CHARS) && cur.length() > 0) {
                flush(chunks, cur);
            }
            cur.append(line).append('\n');
        }
        flush(chunks, cur);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder cur) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) {
            chunks.add(s);
        }
        cur.setLength(0);
    }
}
