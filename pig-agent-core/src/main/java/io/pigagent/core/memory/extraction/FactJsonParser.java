package io.pigagent.core.memory.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, fault-tolerant parser turning the model's JSON reply into {@link ExtractedFact}s.
 *
 * <p>Expected shape (the extractor prompt asks for exactly this): a JSON array (optionally wrapped
 * in a {@code {"facts": [...]}} object, and optionally fenced in a ```` ```json ```` block) of
 * objects with {@code subject} / {@code category} / {@code statement} / {@code confidence} /
 * {@code correction}. Robustness is the whole point here — a real model will occasionally add prose
 * around the JSON, mislabel a category, or omit a field. So:
 * <ul>
 *   <li>the first {@code [...]} / {@code {...}} region is sliced out of surrounding prose;</li>
 *   <li>an unparseable payload yields an <b>empty</b> list (logged), never an exception;</li>
 *   <li>a fact missing subject/statement is skipped; an unknown category falls back to
 *       {@link FactCategory#PROJECT_FACT}; confidence is clamped by {@link ExtractedFact}.</li>
 * </ul>
 */
public final class FactJsonParser {

    private static final Logger log = LoggerFactory.getLogger(FactJsonParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FactJsonParser() {
    }

    /** Parse a raw model reply into facts; never throws, returns empty on any problem. */
    public static List<ExtractedFact> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = sliceJson(raw);
        if (json == null) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("facts");
            if (!array.isArray()) {
                return List.of();
            }
            List<ExtractedFact> facts = new ArrayList<>();
            for (JsonNode node : array) {
                ExtractedFact fact = toFact(node);
                if (fact != null && !fact.isBlank()) {
                    facts.add(fact);
                }
            }
            return facts;
        } catch (Exception e) {
            log.warn("Memory fact JSON parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static ExtractedFact toFact(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String subject = text(node, "subject");
        String statement = text(node, "statement");
        if (subject.isBlank() || statement.isBlank()) {
            return null;
        }
        FactCategory category = FactCategory.fromLabel(text(node, "category"), FactCategory.PROJECT_FACT);
        double confidence = node.path("confidence").asDouble(0.0);
        boolean correction = node.path("correction").asBoolean(false);
        return new ExtractedFact(subject, category, statement, confidence, correction);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("").strip();
    }

    /** Slice the first JSON array/object out of prose; returns null when none is present. */
    private static String sliceJson(String raw) {
        String s = raw.strip();
        int arr = s.indexOf('[');
        int obj = s.indexOf('{');
        int start = min(arr, obj);
        if (start < 0) {
            return null;
        }
        char open = s.charAt(start);
        char close = open == '[' ? ']' : '}';
        int end = s.lastIndexOf(close);
        if (end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    private static int min(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }
}
