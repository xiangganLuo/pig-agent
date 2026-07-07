package io.pigagent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLineageWriterTest {

    @TempDir
    Path sessionsDir;

    @Test
    void recordCompressionAssignsLineageToExistingSession() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session s = repo.save(Session.create("chat"));
        SessionLineageWriter writer = new SessionLineageWriter(repo);

        writer.recordCompression(s.id());

        Optional<Session> after = repo.findById(s.id());
        assertThat(after).isPresent();
        assertThat(after.get().hasCompressionLineage()).isTrue();
        assertThat(after.get().lineageId()).isNotBlank();
        assertThat(after.get().parentSessionId()).isNotBlank();
    }

    @Test
    void recordCompressionIsNoOpForUnknownSession() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        SessionLineageWriter writer = new SessionLineageWriter(repo);

        writer.recordCompression("does-not-exist"); // must not throw
        writer.recordCompression(null);             // must not throw

        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void recordCompressionOnlyTouchesMetaNotPersistedHistory() throws IOException {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session s = repo.save(Session.create("chat"));

        // Simulate persisted conversation history + temp memory living in the same session dir.
        Path dir = sessionsDir.resolve(s.id());
        Path history = dir.resolve("conversation.json");
        Path tempMemory = dir.resolve("temp-memory.md");
        Files.writeString(history, "PERSISTED_HISTORY");
        Files.writeString(tempMemory, "TEMP_MEMORY");

        new SessionLineageWriter(repo).recordCompression(s.id());

        // meta.json gained lineage...
        assertThat(repo.findById(s.id())).get().extracting(Session::hasCompressionLineage).isEqualTo(true);
        // ...but the persisted history and memory files are byte-for-byte unchanged.
        assertThat(Files.readString(history)).isEqualTo("PERSISTED_HISTORY");
        assertThat(Files.readString(tempMemory)).isEqualTo("TEMP_MEMORY");
    }
}
