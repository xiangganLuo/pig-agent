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
            return "# User Profile\n\n- **k**: v\n";
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
}
