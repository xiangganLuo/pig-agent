package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic, model-free classifier that assigns a memory fact to a {@link Layer} — capability
 * {@code memory-layering-and-decay} (M-C, D1). It keys on compiled-in content signals so a fact can be
 * re-derived to its layer on <b>every</b> curate pass (robust to the native consolidation flattening or
 * rewording the layer headings — pig never depends on a stored marker surviving):
 * <ul>
 *   <li><b>{@link Layer#PINNED}</b> — identity / how-to-address-you core (a name declaration, "call me
 *       …"). Kept deliberately <b>narrow</b> (PINNED is the always-injected core) so noise is never
 *       promoted here.</li>
 *   <li><b>{@link Layer#VOLATILE}</b> ("Recent") — episodic / time-bound facts (yesterday / just now /
 *       temporary / this time …): the natural first candidates for decay.</li>
 *   <li><b>{@link Layer#GENERAL}</b> — the durable default: everything not identity-core and not
 *       episodic (durable preferences, project facts, …).</li>
 * </ul>
 *
 * <p>Identity wins over episodic (a name stated "just now" is still identity). Pure/deterministic, no
 * model call, null/blank-tolerant (→ {@code GENERAL}). Latin signals match case-insensitively; CJK
 * signals match by substring. The <b>real</b> "is this really identity vs a throwaway" judgment for
 * arbitrary LLM-worded facts is a deferred live {@code *IT}; this is the offline-provable core.
 */
public final class FactLayerClassifier {

    /** Identity / how-to-address markers → PINNED (narrow on purpose). */
    private static final List<String> IDENTITY_SIGNALS = List.of(
            "我叫", "我的名字", "我名字", "请叫我", "叫我做", "叫我", "我姓",
            "my name is", "call me", "i am called", "i'm called", "you can call me", "name is");

    /** Episodic / time-bound / one-shot markers → VOLATILE. */
    private static final List<String> EPISODIC_SIGNALS = List.of(
            "昨天", "今天", "刚才", "刚刚", "这次", "本次", "临时", "暂时", "待会", "稍后",
            "目前正在", "现在正在", "刚配置", "这个端口", "本次会话",
            "yesterday", "today", "just now", "right now", "temporarily", "temporary",
            "for now", "this time", "at the moment", "currently", "for the moment");

    private final List<String> identitySignals;
    private final List<String> episodicSignals;

    /** Default classifier with the compiled-in identity/episodic signal sets. */
    public FactLayerClassifier() {
        this(IDENTITY_SIGNALS, EPISODIC_SIGNALS);
    }

    /** Seam constructor (test/extension) taking custom signal sets; null → the compiled-in defaults. */
    public FactLayerClassifier(List<String> identitySignals, List<String> episodicSignals) {
        this.identitySignals = identitySignals == null ? IDENTITY_SIGNALS : List.copyOf(identitySignals);
        this.episodicSignals = episodicSignals == null ? EPISODIC_SIGNALS : List.copyOf(episodicSignals);
    }

    /** The layer for {@code factText}; identity → PINNED, episodic → VOLATILE, else GENERAL. */
    public Layer classify(String factText) {
        if (factText == null || factText.isBlank()) {
            return Layer.GENERAL;
        }
        String lower = factText.toLowerCase(Locale.ROOT);
        if (containsAny(lower, identitySignals)) {
            return Layer.PINNED; // identity wins over episodic
        }
        if (containsAny(lower, episodicSignals)) {
            return Layer.VOLATILE;
        }
        return Layer.GENERAL;
    }

    private static boolean containsAny(String lowerText, List<String> signals) {
        for (String s : signals) {
            if (lowerText.contains(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
