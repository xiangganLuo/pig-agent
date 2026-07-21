package io.pigagent.core.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional background <b>profile consolidation</b> — capability {@code user-profile}. Periodically (and
 * throttled) it distills durable identity/preferences out of the consolidated long-term memory
 * ({@code MEMORY.md}) into a deduped {@code USER.md}, via the mockable {@link ProfileDistiller} seam
 * (the live impl runs a cheap model — {@link ModelProfileDistiller}). It is the "background" maintenance
 * path complementing the immediate {@code updateProfile} tool.
 *
 * <p><b>Design.</b> Mirrors {@code CompressionService}: the model call stays behind an injectable seam
 * so the throttle / fault-tolerance / write path are fully testable offline with a deterministic fake.
 *
 * <p><b>Safety.</b> Throttled by {@code minGap} (a {@link Clock} is injectable for tests); a distiller
 * returning {@code null}/blank aborts (profile left as-is); every failure is logged at warn and
 * swallowed — consolidation MUST NOT break a turn, corrupt the profile, or crash. Default-off at the
 * wiring layer (a live cheap-model background job is opt-in).
 */
public final class ProfileConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(ProfileConsolidationService.class);

    private final UserProfileStore store;
    private final Path memoryMd;
    private final ProfileDistiller distiller;
    private final Duration minGap;
    private final Clock clock;

    /** Epoch-ms of the last consolidation; {@code 0} = never (so the first call always runs). */
    private final AtomicLong lastRunAtMs = new AtomicLong(0);

    public ProfileConsolidationService(UserProfileStore store, Path memoryMd, ProfileDistiller distiller,
                                       Duration minGap) {
        this(store, memoryMd, distiller, minGap, Clock.systemUTC());
    }

    /** Seam constructor with an injectable clock (throttle testing). */
    public ProfileConsolidationService(UserProfileStore store, Path memoryMd, ProfileDistiller distiller,
                                       Duration minGap, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.memoryMd = Objects.requireNonNull(memoryMd, "memoryMd");
        this.distiller = Objects.requireNonNull(distiller, "distiller");
        this.minGap = minGap == null || minGap.isNegative() ? Duration.ZERO : minGap;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Consolidate only if the min-gap has elapsed since the last run (background trigger). Returns
     * whether a consolidation actually ran (false when throttled or nothing to do).
     */
    public boolean maybeConsolidate() {
        long now = clock.millis();
        long last = lastRunAtMs.get();
        if (last != 0 && now - last < minGap.toMillis()) {
            return false; // throttled
        }
        return consolidateNow();
    }

    /**
     * Run a consolidation regardless of the throttle (still updates the throttle clock). Fault-tolerant:
     * any failure is logged and swallowed, leaving the existing {@code USER.md} intact.
     */
    public boolean consolidateNow() {
        lastRunAtMs.set(clock.millis());
        try {
            String memory = readMemory();
            String current = store.readFull();
            if (memory.isBlank() && current.isBlank()) {
                return false; // nothing to distill yet
            }
            String distilled = distiller.distill(current, memory);
            if (distilled == null || distilled.isBlank()) {
                return false; // distiller declined (or no cheap model resolvable) — keep the existing profile
            }
            // Conservative, model-free pre-filter (M-E): drop free-form/out-of-whitelist content before
            // it can land in the injected USER.md; SecretRedactor (in store.write) stacks on top. No
            // conforming field → treat as a decline (existing profile left intact).
            String kept = DistilledProfileGuard.filter(distilled);
            if (kept.isBlank()) {
                log.debug("Distilled profile had no conforming identity/preference fields — keeping existing profile");
                return false;
            }
            boolean written = store.write(kept);
            if (written) {
                log.info("User profile consolidated into {}", store.path());
            }
            return written;
        } catch (Exception e) {
            log.warn("Profile consolidation skipped (profile left intact): {}", e.getMessage());
            return false;
        }
    }

    private String readMemory() {
        try {
            if (!Files.isRegularFile(memoryMd)) {
                return "";
            }
            return Files.readString(memoryMd, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read MEMORY.md at {}: {}", memoryMd, e.getMessage());
            return "";
        }
    }
}
