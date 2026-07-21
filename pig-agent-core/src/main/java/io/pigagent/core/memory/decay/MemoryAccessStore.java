package io.pigagent.core.memory.decay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The reuse-signal sidecar — capability {@code memory-layering-and-decay} (M-C, D2). Persists a small,
 * fault-tolerant JSON map {@code fingerprint → {accessCount, lastAccessEpochDay}} at
 * {@code <memoryDir>/.decay/access-state.json}. The <b>dot-prefixed</b> {@code .decay} subdir keeps it
 * invisible to the corpus scanners (native {@code listMemoryFilePaths} / {@code MemoryCorpusLoader} only
 * see top-level {@code memory/YYYY-MM-DD.md}) — so the access sidecar is never ingested as a fact and
 * never re-injected.
 *
 * <p><b>Fault-tolerant by design</b> (mirrors pig's file-backed stores): a missing/corrupt file loads as
 * an empty map (never throws), and a persist failure is logged + swallowed. Loads once on construction;
 * {@link #bump} mutates the in-memory map and atomically rewrites the file. Not a hot path — the reader
 * (curator) constructs a fresh store per pass to see the latest persisted counts.
 */
public final class MemoryAccessStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryAccessStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One fact's reuse record. Public for Jackson (de)serialization. */
    public static final class Entry {
        public int accessCount;
        public long lastAccessEpochDay;

        public Entry() {
        }

        Entry(int accessCount, long lastAccessEpochDay) {
            this.accessCount = accessCount;
            this.lastAccessEpochDay = lastAccessEpochDay;
        }
    }

    private final Path stateFile;
    private final Map<String, Entry> entries;

    /** Construct over {@code memoryDir}'s {@code .decay/access-state.json}, loading it fault-tolerantly. */
    public MemoryAccessStore(Path memoryDir) {
        this.stateFile = Objects.requireNonNull(memoryDir, "memoryDir")
                .resolve(".decay").resolve("access-state.json");
        this.entries = load(stateFile);
    }

    /** The current reuse count for {@code fingerprint} (0 when unknown or blank). */
    public synchronized int accessCount(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return 0;
        }
        Entry e = entries.get(fingerprint);
        return e == null ? 0 : e.accessCount;
    }

    /**
     * Record one reuse of {@code fingerprint} on {@code epochDay}: increment its count, update its last
     * access day, and persist. A blank fingerprint is ignored.
     */
    public synchronized void bump(String fingerprint, long epochDay) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return;
        }
        Entry e = entries.computeIfAbsent(fingerprint, k -> new Entry(0, epochDay));
        e.accessCount++;
        e.lastAccessEpochDay = epochDay;
        persist();
    }

    private static Map<String, Entry> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new HashMap<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new HashMap<>();
            }
            Map<String, Entry> loaded = MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructMapType(HashMap.class, String.class, Entry.class));
            return loaded == null ? new HashMap<>() : loaded;
        } catch (Exception e) {
            log.warn("Access-state sidecar unreadable — starting empty: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private void persist() {
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = Files.createTempFile(stateFile.getParent(), "access-state", ".tmp");
            Files.writeString(tmp, MAPPER.writeValueAsString(entries), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Could not persist access-state sidecar (reuse signal skipped): {}", e.getMessage());
        }
    }
}
