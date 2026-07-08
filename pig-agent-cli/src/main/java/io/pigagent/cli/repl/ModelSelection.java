package io.pigagent.cli.repl;

import io.pigagent.cli.Ansi;
import io.pigagent.cli.repl.select.InlineSelector;
import io.pigagent.model.ModelManager;
import io.pigagent.model.StoredModel;
import io.pigagent.session.SessionManager;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.util.List;
import java.util.OptionalInt;

/**
 * Model selection + activation shared by {@code /model switch <id>} and the no-arg {@code /model}
 * inline picker. Kept out of {@link ReplCommands} so the "selected → manager delegation" path is
 * unit-testable without the whole command tree.
 */
public final class ModelSelection {

    private ModelSelection() {
    }

    /**
     * No-arg {@code /model}: pick a model with the arrow-key selector (numeric fallback on a
     * non-interactive terminal) and activate it for the current session.
     */
    public static void interactive(ReplContext ctx) {
        Terminal t = ctx.terminal();
        ModelManager mm = ctx.modelManager();
        List<StoredModel> models = mm.list();
        if (models.isEmpty()) {
            Ansi.println(t, Ansi.warn("No models saved. Use /model add."));
            return;
        }
        String cur = mm.getCurrentModelId();
        List<String> labels = models.stream()
                .map(m -> m.label() + (m.id().equals(cur) ? " *" : "") + Ansi.dim(" [" + m.id() + "]"))
                .toList();

        OptionalInt picked = InlineSelector.isInteractive(t)
                ? InlineSelector.select(t, "Select a model (↑/↓ move, Enter select, Esc cancel):", labels)
                : numericFallback(ctx, labels);
        if (picked.isEmpty()) {
            Ansi.println(t, Ansi.dim("Cancelled."));
            return;
        }
        apply(t, mm, ctx.sessionManager(), models.get(picked.getAsInt()), false);
    }

    /** Numeric selection for non-interactive terminals: prints the list, reads a 1-based index. */
    private static OptionalInt numericFallback(ReplContext ctx, List<String> labels) {
        Terminal t = ctx.terminal();
        LineReader reader = ctx.readerRef().get();
        if (reader == null) {
            Ansi.println(t, Ansi.error("Interactive input is unavailable."));
            return OptionalInt.empty();
        }
        Ansi.println(t, Ansi.heading("Select a model:"));
        for (int i = 0; i < labels.size(); i++) {
            Ansi.println(t, String.format("  %d) %s", i + 1, labels.get(i)));
        }
        try {
            int idx = Integer.parseInt(reader.readLine("Model number: ").trim()) - 1;
            if (idx >= 0 && idx < labels.size()) {
                return OptionalInt.of(idx);
            }
        } catch (Exception ignored) {
            // fall through to cancel
        }
        return OptionalInt.empty();
    }

    /**
     * Test the model, then activate it — as the global default (all sessions) or just the current
     * session. Returns true when the switch took effect. Delegates entirely to the managers.
     */
    public static boolean apply(Terminal t, ModelManager mm, SessionManager sm, StoredModel m, boolean global) {
        Ansi.println(t, Ansi.dim("Testing " + m.label() + " ..."));
        ModelManager.TestResult test = mm.test(m);
        if (!test.ok()) {
            Ansi.println(t, Ansi.error("Model unavailable: " + test.error() + " — keeping current model."));
            return false;
        }
        if (global) {
            mm.setDefault(m.id());
            sm.bindCurrentSessionModel(null);
            sm.reactivateCurrent();
            Ansi.println(t, Ansi.success("Global default set to " + m.label() + " (applies to all sessions)."));
        } else {
            sm.bindCurrentSessionModel(m.id());
            sm.reactivateCurrent();
            Ansi.println(t, Ansi.success("This session now uses " + m.label()
                    + " (new sessions keep the default)."));
        }
        return true;
    }
}
