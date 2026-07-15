package io.pigagent.core.loop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Default {@link ToolCallSignatureStrategy}. Borrowed from deerflow's loop-detection heuristics:
 *
 * <ul>
 *   <li><b>{@code readFile} — bucket by 200-line ranges.</b> Signature = {@code readFile|<path>|#<bucket>}
 *       where {@code bucket = max(0, startLine) / 200}. Re-reading the <em>same file</em> with only
 *       slightly different line ranges (that fall in the same 200-line bucket) collapses to the same
 *       signature, so a "read the same region again and again" loop is caught even when the model
 *       nudges the offset. The start line is read from the first present of a small set of common
 *       line-offset parameter names; absent ⇒ 0 (so a paramless {@code readFile} of one path always
 *       maps to bucket 0 — exactly the desired "re-reading the same file counts as repetition").</li>
 *   <li><b>{@code writeFile} / edits / everything else — hash the full args.</b> Signature =
 *       {@code toolName|sha256(canonical(input))}. Writing the same file with <em>different</em>
 *       content is not a loop, so writes are never bucketed — only identical name+args repeat. The
 *       hash keeps signatures fixed-size regardless of a large {@code content} argument.</li>
 * </ul>
 *
 * <p>Canonicalization sorts map keys recursively so signature equality is independent of argument
 * ordering. This class is pure and stateless.
 */
public final class DefaultToolCallSignatureStrategy implements ToolCallSignatureStrategy {

    /** The one tool whose signature is bucketed by line range rather than full-arg hashed. */
    static final String READ_TOOL = "readFile";

    /** Lines per bucket: reads within the same 200-line window collapse to one signature. */
    static final int LINE_BUCKET_SIZE = 200;

    /** Parameter names (any one) that carry a 0-based/1-based start line for {@code readFile}. */
    private static final List<String> START_LINE_KEYS =
            List.of("offset", "start_line", "startLine", "start", "line", "from", "begin");

    /** Parameter names (any one) that carry the file path for {@code readFile}. */
    private static final List<String> PATH_KEYS = List.of("path", "file", "file_path", "filename");

    @Override
    public String signature(String toolName, Map<String, Object> input) {
        String name = toolName == null ? "" : toolName;
        Map<String, Object> args = input == null ? Map.of() : input;
        if (READ_TOOL.equals(name)) {
            return readFileSignature(args);
        }
        return name + "|" + sha256Hex(canonical(args));
    }

    private static String readFileSignature(Map<String, Object> args) {
        String path = firstString(args, PATH_KEYS);
        long startLine = Math.max(0L, firstLong(args, START_LINE_KEYS));
        long bucket = startLine / LINE_BUCKET_SIZE;
        return READ_TOOL + "|" + path + "|#" + bucket;
    }

    private static String firstString(Map<String, Object> args, List<String> keys) {
        for (String key : keys) {
            Object v = args.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static long firstLong(Map<String, Object> args, List<String> keys) {
        for (String key : keys) {
            Object v = args.get(key);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v != null) {
                try {
                    return Long.parseLong(String.valueOf(v).trim());
                } catch (NumberFormatException ignore) {
                    // not a number — treat as absent and keep scanning
                }
            }
        }
        return 0L;
    }

    /** Deterministic, key-order-independent rendering of arbitrary tool args. */
    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('=').append(canonical(e.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonical(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; fall back to the raw string if not.
            return s;
        }
    }
}
