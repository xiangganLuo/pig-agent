package io.pigagent.core.profile;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.pigagent.core.agent.PigAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The live {@link ProfileDistiller}: spins up a throwaway agent on a <b>cheap model</b> to rewrite the
 * curated {@code USER.md} from the current profile + the consolidated long-term memory — capability
 * {@code user-profile}. Mirrors {@code CompressionService.ModelSummarizer} (reusing the
 * {@code pa-memory-native} cheap-model pattern rather than building a second engine).
 *
 * <p>The cheap model is supplied lazily (config {@code user-profile.consolidation.model-id} →
 * {@code memory.model-id} → the primary reasoning model), so a model switch is picked up. Real
 * distillation quality is a live-model concern verified by {@code *IT}; a null/blank return (or a null
 * model) aborts the consolidation, leaving the profile unchanged.
 */
public final class ModelProfileDistiller implements ProfileDistiller {

    private static final Logger log = LoggerFactory.getLogger(ModelProfileDistiller.class);

    private static final String DISTILL_PROMPT = """
            You maintain a concise USER PROFILE for a personal assistant. From the CURRENT PROFILE and
            the user's long-term MEMORY below, produce an updated profile capturing only DURABLE facts
            about THIS user: their identity (name, how to address them, role) and standing preferences
            (language, output style, technology preferences, working style, timezone).

            If the CURRENT PROFILE is empty, SEED it from MEMORY: extract the user's name / how to
            address them and any durable preferences that are clearly about the user.

            Rules:
            - Include ONLY high-confidence, durable facts explicitly about the user. When in doubt, OMIT —
              do NOT infer, guess, or invent. It is better to leave a field out than to state it wrongly.
            - DROP transient/task-specific details, one-off events, and anything not about the user
              (other people, projects, and sensitive personal data such as health or physical address).
            - DEDUPE; if the current profile already states something, keep it unless MEMORY clearly
              supersedes it.
            - NEVER include secrets, API keys or tokens.
            - Output ONLY the profile as Markdown, starting with "# User Profile", then one
              "- **Field**: value" line per fact. Use conservative field labels such as: name,
              How to address you, role, language, output style, technology preferences, working style,
              timezone. Do NOT add free-form prose or non-identity/preference fields.
            """;

    private final Supplier<Model> modelSupplier;

    public ModelProfileDistiller(Supplier<Model> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier");
    }

    @Override
    public String distill(String currentProfile, String memory) {
        Model model = modelSupplier.get();
        if (model == null) {
            log.debug("No model resolvable for profile distillation — skipping");
            return null;
        }
        PigAgent distiller = PigAgent.builder()
                .name("profile-distiller")
                .sysPrompt(DISTILL_PROMPT)
                .model(model)
                .build();
        try {
            String input = "CURRENT PROFILE:\n" + (currentProfile == null ? "" : currentProfile)
                    + "\n\nMEMORY:\n" + (memory == null ? "" : memory);
            Msg reply = distiller.call(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text(input).build()).build());
            return reply == null ? null : reply.getTextContent();
        } finally {
            distiller.close();
        }
    }
}
