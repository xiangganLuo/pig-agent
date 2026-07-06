package io.pigagent.core.retry;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a model-call failure is <em>transient</em> (worth retrying) or
 * <em>permanent</em> (fail fast). Transient: HTTP 5xx (e.g. 502/503), timeouts, network/IO.
 * Permanent: HTTP 4xx (401/403/400…). Unknown errors default to permanent (conservative — we
 * don't hammer an upstream on failures we can't classify).
 *
 * <p>Classification is by exception type (walking the cause chain) plus HTTP-status tokens in the
 * message, so it doesn't depend on the concrete exception class the model SDK throws.
 */
public final class TransientErrorClassifier {

    private static final Pattern STATUS = Pattern.compile("\\b([1-5]\\d\\d)\\b");

    /** True if the error should be retried. */
    public boolean isTransient(Throwable error) {
        if (error == null) {
            return false;
        }
        // Type-based: timeouts and network/IO anywhere in the cause chain are transient.
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof IOException) {
                return true;
            }
            if (t == t.getCause()) {
                break; // guard against self-referential cause cycles
            }
        }
        // Status-based: a 4xx anywhere means permanent (wins); otherwise a 5xx means transient.
        String message = collectMessages(error);
        boolean has4xx = false;
        boolean has5xx = false;
        Matcher m = STATUS.matcher(message);
        while (m.find()) {
            int code = Integer.parseInt(m.group(1));
            if (code >= 400 && code < 500) {
                has4xx = true;
            } else if (code >= 500 && code < 600) {
                has5xx = true;
            }
        }
        if (has4xx) {
            return false;
        }
        return has5xx;
    }

    private String collectMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return sb.toString();
    }
}
