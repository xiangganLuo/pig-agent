package io.pigagent.core.profile;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Injects the curated user profile ({@code USER.md}) into the <b>system prompt</b> — capability
 * {@code user-profile} (Hermes {@code USER.md} blueprint). This is the profile sibling of
 * {@code NativeMemoryContextMiddleware} (which injects the consolidated {@code MEMORY.md}).
 *
 * <p><b>Ordering.</b> {@code onSystemPrompt} is a Transformer hook (left-to-right pipeline). This
 * middleware is placed <em>before</em> the memory middleware in the chain, so the assembled prompt is
 * {@code base + USER + MEMORY} — the profile ("who you are") appears before the general fact ledger, as
 * Hermes does (both frozen into the cacheable system-prompt prefix).
 *
 * <p><b>Self-gating.</b> When profile is disabled ({@code enabled} supplier → false) this stage is the
 * identity transform (prompt unchanged) — so {@code user-profile.enabled=false} is byte-for-byte the
 * pre-feature behaviour, with no per-agent rebuild needed.
 *
 * <p><b>Prefix-cache friendly + fault-tolerant.</b> {@code USER.md} changes rarely, so the prompt is
 * byte-stable turn-to-turn; a missing/blank/unreadable file (or a disabled flag) leaves the prompt
 * untouched. The file is read fresh (bounded + credential-redacted by {@link UserProfileStore}) each
 * call so an {@code updateProfile}/consolidation is picked up on the next turn. Stateless (final fields
 * only) → one instance is safely shared across the interactive / channel / peer agents.
 */
public final class UserProfileContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(UserProfileContextMiddleware.class);

    /** Header prefixing the injected profile block in the system prompt. */
    static final String PROFILE_HEADER = "## User Profile (USER.md)";

    private final UserProfileStore store;
    private final int maxChars;
    private final BooleanSupplier enabled;

    /**
     * @param store the {@code USER.md} store (bounds + redacts on read).
     * @param maxChars injection size cap (≤ 0 = unbounded).
     * @param enabled read live so {@code user-profile.enabled} toggles injection without a rebuild;
     *        {@code null} → always enabled.
     */
    public UserProfileContextMiddleware(UserProfileStore store, int maxChars, BooleanSupplier enabled) {
        this.store = Objects.requireNonNull(store, "store");
        this.maxChars = maxChars;
        this.enabled = enabled == null ? () -> true : enabled;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (!isEnabled()) {
            return Mono.justOrEmpty(currentPrompt);
        }
        String profile = store.read(maxChars);
        if (profile.isBlank()) {
            return Mono.justOrEmpty(currentPrompt);
        }
        String base = currentPrompt != null ? currentPrompt : "";
        String separator = base.isEmpty() || base.endsWith("\n") ? "" : "\n\n";
        return Mono.just(base + separator + PROFILE_HEADER + "\n" + profile.strip() + "\n");
    }

    private boolean isEnabled() {
        try {
            return enabled.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("USER.md enabled check failed, treating as disabled: {}", e.getMessage());
            return false;
        }
    }
}
