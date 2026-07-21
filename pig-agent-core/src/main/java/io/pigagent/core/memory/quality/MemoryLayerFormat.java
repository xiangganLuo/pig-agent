package io.pigagent.core.memory.quality;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stable {@code MEMORY.md} <b>layer format contract</b> — capability
 * {@code memory-consolidation-quality} (D6). It defines a small, documented set of layer sections a
 * structured consolidation MAY emit (a pinned/core layer, a general layer, a volatile/recent layer),
 * each keyed by a canonical Markdown {@code ##} heading, so a future <b>layered-decay</b> capability
 * (M-C) has a stable interface to key on. This class only <b>defines and reads</b> the contract; it
 * deliberately does NOT implement decay (that is M-C, out of scope here — avoid over-building it).
 *
 * <p><b>Backward compatible.</b> {@link #parse(String)} of an old, unstructured {@code MEMORY.md} (no
 * matching headings) returns everything under {@link Layer#GENERAL} — a legacy file is simply treated as
 * a single layer, never an error.
 */
public final class MemoryLayerFormat {

    /** The three durability layers, most-durable first. */
    public enum Layer {
        /** Stable identity / strong preferences — the always-relevant core. */
        PINNED("Pinned"),
        /** General durable facts — the default bucket (and where unmarked legacy content lands). */
        GENERAL("General"),
        /** Recent, lower-durability facts — the natural first candidates for future decay. */
        VOLATILE("Recent");

        private final String heading;

        Layer(String heading) {
            this.heading = heading;
        }

        /** The canonical {@code ##} heading text for this layer (e.g. {@code "Pinned"}). */
        public String heading() {
            return heading;
        }
    }

    private MemoryLayerFormat() {
    }

    /** The {@link Layer} whose canonical heading equals {@code heading} (case-insensitive), else null. */
    public static Layer layerForHeading(String heading) {
        if (heading == null) {
            return null;
        }
        String h = heading.strip();
        for (Layer layer : Layer.values()) {
            if (layer.heading.equalsIgnoreCase(h)) {
                return layer;
            }
        }
        return null;
    }

    /**
     * Parse {@code md} into its layer sections (body text per layer, in {@link Layer} order). Content
     * before any recognized layer heading — and the whole file when it has no layer headings at all —
     * lands under {@link Layer#GENERAL} (backward compatible: a legacy unstructured file is a single
     * layer). A layer absent from the file simply has no entry. Never throws.
     */
    public static Map<Layer, String> parse(String md) {
        Map<Layer, StringBuilder> buckets = new EnumMap<>(Layer.class);
        Layer current = Layer.GENERAL; // pre-heading / legacy content is GENERAL
        if (md != null) {
            for (String line : md.split("\n", -1)) {
                Layer target = headingLayer(line);
                if (target != null) {
                    current = target; // switch layer, drop the heading line itself from the body
                    buckets.computeIfAbsent(current, k -> new StringBuilder());
                    continue;
                }
                buckets.computeIfAbsent(current, k -> new StringBuilder())
                        .append(line).append('\n');
            }
        }
        Map<Layer, String> out = new LinkedHashMap<>();
        for (Layer layer : Layer.values()) {
            StringBuilder b = buckets.get(layer);
            if (b != null) {
                String body = b.toString().strip();
                if (!body.isEmpty()) {
                    out.put(layer, body);
                }
            }
        }
        return out;
    }

    /** If {@code line} is a {@code ## <layer-heading>} matching a known layer, return that layer. */
    private static Layer headingLayer(String line) {
        String s = line.strip();
        int hashes = 0;
        while (hashes < s.length() && s.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes == 0 || hashes > 6 || hashes >= s.length()) {
            return null;
        }
        return layerForHeading(s.substring(hashes).strip());
    }
}
