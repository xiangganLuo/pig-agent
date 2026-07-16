package io.pigagent.core.outreach;

import java.util.List;

/**
 * An optional action attached to a {@link Notification} — e.g. a yes/no request the user can answer.
 * Immutable; it only carries the prompt text and option labels. The actual reply comes back through
 * the normal channel <em>inbound</em> path (this type does not bind a two-way callback).
 */
public record OutreachAction(String prompt, List<String> options) {

    public OutreachAction {
        prompt = prompt == null ? "" : prompt;
        options = options == null ? List.of() : List.copyOf(options);
    }

    /** A yes/no decision request. */
    public static OutreachAction yesNo(String prompt) {
        return new OutreachAction(prompt, List.of("yes", "no"));
    }
}
