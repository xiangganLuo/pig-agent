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
            about the user: their identity (name, how to address them), standing preferences (language,
            output style, technology preferences) and working style. Rules: keep it short and curated;
            DEDUPE; DROP transient/task-specific details and anything that is not about the user; if the
            current profile already states something, keep it unless the memory clearly supersedes it;
            never include secrets, API keys or tokens. Output ONLY the profile as Markdown, starting with
            "# User Profile" and one "- **Field**: value" line per fact.
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
