package io.pigagent.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link ProfileConsolidationService} (capability {@code user-profile}): the
 * mockable {@link ProfileDistiller} seam drives the write path, the min-gap throttle is honored, a
 * failing/declining distiller degrades safely (profile intact), and nothing runs with no input.
 */
class ProfileConsolidationServiceTest {

    /** A test clock whose millis we can advance. */
    private static final class MovableClock extends Clock {
        private long millis;
        MovableClock(long start) { this.millis = start; }
        void advance(Duration d) { this.millis += d.toMillis(); }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
    }

    @Test
    void consolidate_distillsMemoryIntoProfile_viaMockSeam(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "user said their name is 罗湘赣; prefers Chinese", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        AtomicReference<String> seenMemory = new AtomicReference<>();
        ProfileDistiller distiller = (profile, memory) -> {
            seenMemory.set(memory);
            return "# User Profile\n\n- **name**: 罗湘赣\n- **language**: Chinese\n";
        };
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, distiller, Duration.ofMinutes(60), new MovableClock(0));

        boolean ran = svc.consolidateNow();

        assertThat(ran).isTrue();
        assertThat(seenMemory.get()).contains("罗湘赣");
        assertThat(store.readFull()).contains("- **name**: 罗湘赣").contains("- **language**: Chinese");
    }

    @Test
    void maybeConsolidate_throttledWithinMinGap(@TempDir Path ws) throws IOException {
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "some memory", StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        ProfileDistiller distiller = (profile, memory) -> {
            calls.incrementAndGet();
            return "# User Profile\n\n- **name**: v\n"; // whitelisted field so the conservative filter keeps it
        };
        MovableClock clock = new MovableClock(1000);
        ProfileConsolidationService svc = new ProfileConsolidationService(
                new UserProfileStore(ws.resolve("USER.md")), memoryMd, distiller, Duration.ofMinutes(30), clock);

        assertThat(svc.maybeConsolidate()).isTrue();  // first run
        assertThat(svc.maybeConsolidate()).isFalse(); // throttled
        assertThat(calls.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(31));
        assertThat(svc.maybeConsolidate()).isTrue();  // gap elapsed
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void distillerThrows_isSafelySwallowed_profileIntact(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        store.updateField("name", "existing");
        ProfileDistiller boom = (profile, memory) -> { throw new RuntimeException("model down"); };
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, boom, Duration.ZERO, new MovableClock(0));

        boolean ran = svc.consolidateNow();

        assertThat(ran).isFalse();
        assertThat(store.readFull()).contains("- **name**: existing"); // intact
    }

    @Test
    void blankDistillerResult_keepsExistingProfile(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        store.updateField("name", "keep");
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, (p, m) -> "   ", Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isFalse();
        assertThat(store.readFull()).contains("- **name**: keep");
    }

    @Test
    void noMemoryAndNoProfile_doesNothing(@TempDir Path ws) {
        AtomicInteger calls = new AtomicInteger();
        ProfileConsolidationService svc = new ProfileConsolidationService(
                new UserProfileStore(ws.resolve("USER.md")), ws.resolve("MEMORY.md"),
                (p, m) -> { calls.incrementAndGet(); return "x"; }, Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isFalse();
        assertThat(calls.get()).isZero(); // distiller never invoked with nothing to distill
    }

    // --- M-E: seeding, conservative pre-filter, no-model no-op ---

    @Test
    void seeding_emptyProfile_passesBlankCurrentToSeam_andWritesSeededIdentity(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md"); // absent → empty profile = seeding branch
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "The user's name is 罗湘赣 and prefers Chinese.", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        AtomicReference<String> seenCurrent = new AtomicReference<>("SENTINEL");
        ProfileDistiller seeder = (current, memory) -> {
            seenCurrent.set(current);
            return "# User Profile\n\n- **name**: 罗湘赣\n- **language**: Chinese\n";
        };
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, seeder, Duration.ZERO, new MovableClock(0));

        boolean ran = svc.consolidateNow();

        assertThat(ran).isTrue();
        assertThat(seenCurrent.get()).isBlank(); // seeding branch: empty current profile
        assertThat(store.readFull()).contains("- **name**: 罗湘赣").contains("- **language**: Chinese");
    }

    @Test
    void seeding_noSeedableIdentity_doesNotCreateUserMd(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "unrelated logs with no user identity", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        ProfileDistiller seeder = (current, memory) -> ""; // nothing to seed → declines

        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, seeder, Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isFalse();
        assertThat(Files.exists(userMd)).isFalse(); // never created
    }

    @Test
    void consolidate_appliesConservativeFilterBeforeWrite(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        ProfileDistiller distiller = (c, m) -> """
                # User Profile

                Some free-form narrative the model decided to add.
                - **name**: 罗湘赣
                - **Current Task**: refactor the parser
                - **language**: Chinese
                """;
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, distiller, Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isTrue();
        String written = store.readFull();
        assertThat(written).contains("- **name**: 罗湘赣").contains("- **language**: Chinese");
        assertThat(written).doesNotContain("free-form narrative").doesNotContain("Current Task");
    }

    @Test
    void consolidate_filterDropsEverything_keepsExistingProfile(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        store.updateField("name", "existing");
        ProfileDistiller distiller = (c, m) -> "# User Profile\n\nplain prose\n- **Current Task**: x\n";
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, distiller, Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isFalse();
        assertThat(store.readFull()).contains("- **name**: existing"); // intact
    }

    @Test
    void consolidate_redactsSecretInKeptField(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        // A whitelisted field whose value looks like a credential: the filter keeps the line,
        // store.write's SecretRedactor masks the secret (the two gates stack).
        ProfileDistiller distiller = (c, m) -> "# User Profile\n\n- **name**: sk-abcdef123456\n";
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, distiller, Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isTrue();
        assertThat(store.readFull()).doesNotContain("sk-abcdef123456").contains("***");
    }

    @Test
    void consolidate_noOp_whenNoCheapModelResolvable(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory with an identity", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        // Live distiller with no resolvable model → distill() returns null → no write, no crash.
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, new ModelProfileDistiller(() -> null), Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isFalse();
        assertThat(Files.exists(userMd)).isFalse();
    }

    @Test
    void seeding_distillerThrows_userMdNotCreated(@TempDir Path ws) throws IOException {
        Path userMd = ws.resolve("USER.md");
        Path memoryMd = ws.resolve("MEMORY.md");
        Files.writeString(memoryMd, "memory with 罗湘赣", StandardCharsets.UTF_8);
        UserProfileStore store = new UserProfileStore(userMd);
        ProfileDistiller boom = (c, m) -> { throw new RuntimeException("model down"); };
        ProfileConsolidationService svc = new ProfileConsolidationService(
                store, memoryMd, boom, Duration.ZERO, new MovableClock(0));

        assertThat(svc.consolidateNow()).isFalse();
        assertThat(Files.exists(userMd)).isFalse(); // seeding failure never creates/corrupts the file
    }
}
