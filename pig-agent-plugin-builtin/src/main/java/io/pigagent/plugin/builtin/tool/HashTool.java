package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-compute cryptographic-digest tools (MD5, SHA-256) over UTF-8 text, returning lowercase hex.
 * No I/O. Failures return the canonical {@code {"error"}}.
 */
public final class HashTool {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Tool(name = "md5Hash", readOnly = true,
            description = "Compute the MD5 digest of UTF-8 text as lowercase hex.")
    public String md5Hash(@ToolParam(name = "text", description = "text to hash") String text) {
        return digest("MD5", text);
    }

    @Tool(name = "sha256Hash", readOnly = true,
            description = "Compute the SHA-256 digest of UTF-8 text as lowercase hex.")
    public String sha256Hash(@ToolParam(name = "text", description = "text to hash") String text) {
        return digest("SHA-256", text);
    }

    private static String digest(String algorithm, String text) {
        String value = text == null ? "" : text;
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            return ToolErrors.message("digest algorithm unavailable: " + algorithm);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
