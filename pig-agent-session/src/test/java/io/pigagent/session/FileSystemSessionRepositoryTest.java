package io.pigagent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemSessionRepositoryTest {

    @TempDir
    Path sessionsDir;

    @Test
    void saveThenFindByIdRoundTrips() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session saved = repo.save(Session.create("analysis"));

        Optional<Session> found = repo.findById(saved.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("analysis");
        assertThat(found.get().id()).isEqualTo(saved.id());
        assertThat(found.get().corrupt()).isFalse();
        // Timestamps survive the epoch-millis round-trip (to the millisecond).
        assertThat(found.get().createdAt().toEpochMilli()).isEqualTo(saved.createdAt().toEpochMilli());
    }

    @Test
    void modelIdRoundTrips() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session s = repo.save(Session.create("bound").withModelId("model-42"));

        assertThat(repo.findById(s.id())).get().extracting(Session::modelId).isEqualTo("model-42");
    }

    @Test
    void findAllReturnsEverySavedSession() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        repo.save(Session.create("one"));
        repo.save(Session.create("two"));

        List<Session> all = repo.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Session::name).containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void renameViaSaveOverwritesMetadata() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session s = repo.save(Session.create("old"));
        repo.save(s.withName("new"));

        assertThat(repo.findById(s.id())).get().extracting(Session::name).isEqualTo("new");
    }

    @Test
    void deleteRemovesSessionDirectory() {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session s = repo.save(Session.create("temp"));
        assertThat(sessionsDir.resolve(s.id())).exists();

        repo.deleteById(s.id());
        assertThat(repo.findById(s.id())).isEmpty();
        assertThat(sessionsDir.resolve(s.id())).doesNotExist();
    }

    @Test
    void corruptMetadataIsSkippedNotThrown() throws IOException {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Session good = repo.save(Session.create("good"));

        // A second session dir with an unreadable meta.json must not break listing.
        Path badDir = sessionsDir.resolve("badid");
        Files.createDirectories(badDir);
        Files.writeString(badDir.resolve("meta.json"), "{ this is not valid json");

        List<Session> all = repo.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(s -> {
            assertThat(s.id()).isEqualTo(good.id());
            assertThat(s.corrupt()).isFalse();
        });
        assertThat(all).anySatisfy(s -> {
            assertThat(s.id()).isEqualTo("badid");
            assertThat(s.corrupt()).isTrue();
        });
    }

    @Test
    void directoryWithoutMetadataIsNotListed() throws IOException {
        SessionRepository repo = new FileSystemSessionRepository(sessionsDir);
        Files.createDirectories(sessionsDir.resolve("orphan")); // AgentScope state but no meta yet

        assertThat(repo.findAll()).isEmpty();
        assertThat(repo.findById("orphan")).isEmpty();
    }
}
