package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure-compute random generators: a random integer in an inclusive range, and a random string over a
 * chosen alphabet. Uses {@link ThreadLocalRandom} — general-purpose, NOT cryptographically secure (do
 * not use for keys/tokens). No I/O. Failures return the canonical {@code {"error"}}.
 */
public final class RandomTool {

    private static final int MAX_LENGTH = 4096;
    private static final String ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @Tool(name = "randomNumber",
            description = "Random integer in the inclusive range [min, max]. Not cryptographically secure.")
    public String randomNumber(
            @ToolParam(name = "min", description = "inclusive lower bound") long min,
            @ToolParam(name = "max", description = "inclusive upper bound (>= min)") long max) {
        if (min > max) {
            return ToolErrors.message("min must be <= max (min=" + min + ", max=" + max + ")");
        }
        // nextLong upper bound is exclusive; guard against overflow when max == Long.MAX_VALUE.
        long value = max == Long.MAX_VALUE
                ? ThreadLocalRandom.current().nextLong(min, max)
                : ThreadLocalRandom.current().nextLong(min, max + 1);
        return Long.toString(value);
    }

    @Tool(name = "randomString",
            description = "Random string of a given length. charset: 'alphanumeric' (default), "
                    + "'alpha', 'numeric', or 'hex'. Not cryptographically secure.")
    public String randomString(
            @ToolParam(name = "length", description = "number of characters (1..4096)") int length,
            @ToolParam(name = "charset", required = false,
                    description = "'alphanumeric' (default), 'alpha', 'numeric', or 'hex'") String charset) {
        if (length < 1 || length > MAX_LENGTH) {
            return ToolErrors.message("length out of range (1.." + MAX_LENGTH + "): " + length);
        }
        String alphabet = alphabetFor(charset);
        if (alphabet == null) {
            return ToolErrors.message("invalid charset (use alphanumeric|alpha|numeric|hex): " + charset);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String alphabetFor(String charset) {
        String c = charset == null || charset.isBlank() ? "alphanumeric" : charset.trim().toLowerCase();
        return switch (c) {
            case "alphanumeric" -> ALPHANUMERIC;
            case "alpha" -> ALPHANUMERIC.substring(0, 52);
            case "numeric" -> "0123456789";
            case "hex" -> "0123456789abcdef";
            default -> null;
        };
    }
}
