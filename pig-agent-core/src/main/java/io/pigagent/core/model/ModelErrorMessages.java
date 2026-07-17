package io.pigagent.core.model;

import io.pigagent.core.profile.SecretRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps a model-call {@link Throwable} to a short, localized (Chinese) one-liner for the user — so a
 * failed model call surfaces as "模型限流，请稍后再试" rather than a raw stack trace / HTTP body. Pure,
 * side-effect-free and null-safe: it never throws and never echoes a credential.
 *
 * <p>Classification is defensive — the AgentScope openai/anthropic model extensions surface the HTTP
 * status and error kind in the exception's message and/or class name, which vary across SDKs, so this
 * matches on <em>both</em> the status-code text and known keywords across the whole cause chain,
 * ordered most-specific-first. Anything unrecognized falls through to a generic message carrying a
 * <em>sanitized</em> short reason (credentials — {@code sk-} keys, {@code Bearer} tokens, bare
 * {@code AIza…}/{@code ghp_…}/{@code xox…}/JWT tokens, {@code key=…} assignments — are masked first).
 *
 * <p>Lives in {@code pig-agent-core} (not {@code pig-agent-tools}, which depends on core) so both the
 * REPL error path and {@code ModelManager}'s connectivity test can reuse one taxonomy.
 */
public final class ModelErrorMessages {

    static final String FALLBACK_PREFIX = "模型调用失败：";

    private static final String RATE_LIMIT = "模型限流（请求过于频繁），已自动重试仍失败，请稍后再试。";
    private static final String UPSTREAM = "模型服务暂时不可用（上游过载/故障），请稍后再试。";
    private static final String AUTH = "API Key 无效或已失效，请用 /model edit 更新密钥。";
    private static final String FORBIDDEN = "访问被拒（账户欠费/无权限/地域限制），请检查账户状态。";
    private static final String BAD_REQUEST = "请求无效（可能上下文过长），可试 /compress now 或换模型。";
    private static final String NOT_FOUND = "模型名不存在，请用 /model edit 修正 model name。";
    private static final String TIMEOUT = "模型响应超时，请检查网络或稍后再试。";
    private static final String NETWORK = "无法连接模型服务，请检查网络或 base URL。";

    /** Max cause-chain depth walked, guarding against a pathological self-referential chain. */
    private static final int MAX_CHAIN = 12;
    /** Max length of the sanitized short reason in the fallthrough message. */
    private static final int MAX_REASON = 160;

    // Bare-token shapes (no {@code key=} prefix) that {@link SecretRedactor} does not catch. Kept in
    // sync with the REPL's ToolCallFormatter.redact by intent; duplicated across the module boundary.
    private static final Pattern GOOGLE_KEY = Pattern.compile("AIza[0-9A-Za-z_\\-]{20,}");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("gh[pousr]_[0-9A-Za-z]{20,}");
    private static final Pattern SLACK_TOKEN =
            Pattern.compile("xox[baprs]-[0-9A-Za-z-]{10,}|xapp-[0-9A-Za-z-]{10,}");
    private static final Pattern JWT =
            Pattern.compile("eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");

    private ModelErrorMessages() {
    }

    /** Map a (possibly null / wrapped) throwable to a localized, credential-safe one-liner. */
    public static String friendly(Throwable t) {
        if (t == null) {
            return FALLBACK_PREFIX + "未知错误。";
        }
        List<Throwable> chain = chain(t);
        String hay = haystack(chain);

        if (matchesClass(chain, "HttpTimeoutException", "TimeoutException")
                || containsAny(hay, "timed out", "timeout", "read timed out")) {
            return TIMEOUT;
        }
        if (hasCode(hay, "429") || containsAny(hay, "too many requests", "rate limit",
                "rate_limit", "ratelimit", "requests per minute")) {
            return RATE_LIMIT;
        }
        if (hasCode(hay, "529") || hasCode(hay, "500") || hasCode(hay, "502") || hasCode(hay, "503")
                || hasCode(hay, "504") || containsAny(hay, "overloaded", "overload",
                "internal server error", "bad gateway", "service unavailable", "gateway timeout",
                "server_error", "upstream")) {
            return UPSTREAM;
        }
        if (hasCode(hay, "401") || containsAny(hay, "unauthorized", "invalid api key",
                "invalid_api_key", "invalid_authentication", "authentication_error",
                "invalid x-api-key", "incorrect api key")) {
            return AUTH;
        }
        if (hasCode(hay, "403") || containsAny(hay, "forbidden", "permission_denied",
                "insufficient", "quota", "overdue", "arrears", "欠费", "region",
                "unsupported_country", "access denied")) {
            return FORBIDDEN;
        }
        if (hasCode(hay, "404") || containsAny(hay, "not found", "model_not_found",
                "does not exist", "no such model", "unknown model")) {
            return NOT_FOUND;
        }
        if (hasCode(hay, "400") || hasCode(hay, "422") || containsAny(hay, "bad request",
                "invalid request", "invalid_request", "context length", "context_length",
                "maximum context", "too long", "too many tokens", "context window")) {
            return BAD_REQUEST;
        }
        if (matchesClass(chain, "UnknownHostException", "ConnectException", "IOException",
                "SocketException", "SSLException")
                || containsAny(hay, "connection refused", "failed to connect", "unknownhost",
                "no route to host", "connection reset", "network is unreachable")) {
            return NETWORK;
        }
        return FALLBACK_PREFIX + shortReason(t) + "。";
    }

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> out = new ArrayList<>();
        Throwable cur = t;
        while (cur != null && out.size() < MAX_CHAIN && !out.contains(cur)) {
            out.add(cur);
            cur = cur.getCause();
        }
        return out;
    }

    /** Lowercased blob of every message + simple class name in the chain, for keyword/code matching. */
    private static String haystack(List<Throwable> chain) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c : chain) {
            sb.append(c.getClass().getSimpleName()).append(' ');
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesClass(List<Throwable> chain, String... simpleNames) {
        for (Throwable c : chain) {
            String cn = c.getClass().getSimpleName();
            for (String n : simpleNames) {
                if (cn.equals(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the (lowercased) haystack contains {@code code} as a standalone token (word-bounded). */
    private static boolean hasCode(String haystack, String code) {
        int from = 0;
        while (true) {
            int i = haystack.indexOf(code, from);
            if (i < 0) {
                return false;
            }
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(haystack.charAt(i - 1));
            int end = i + code.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = i + 1;
        }
    }

    /** A sanitized, single-line, length-capped reason for the fallthrough message. Never a credential. */
    static String shortReason(Throwable t) {
        String m = t.getMessage();
        String reason = (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m.strip();
        reason = SecretRedactor.redact(reason);
        reason = maskBareTokens(reason);
        reason = reason.replaceAll("\\s+", " ").strip();
        if (reason.isEmpty()) {
            reason = t.getClass().getSimpleName();
        }
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON) + "…";
        }
        return reason;
    }

    /** Mask bare (unprefixed) provider tokens {@link SecretRedactor} does not cover. */
    static String maskBareTokens(String s) {
        String out = s;
        out = GOOGLE_KEY.matcher(out).replaceAll("***");
        out = GITHUB_TOKEN.matcher(out).replaceAll("***");
        out = SLACK_TOKEN.matcher(out).replaceAll("***");
        out = JWT.matcher(out).replaceAll("***");
        return out;
    }
}
