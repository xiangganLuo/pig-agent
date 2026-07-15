package io.pigagent.core.memory.extraction;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.compression.MsgContentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * LLM-backed {@link MemoryExtractor}: spins up a throwaway {@link PigAgent} on the <em>current</em>
 * model (same "aux model call on the live model" pattern as compression's {@code ModelSummarizer})
 * and asks it to extract classified, scored facts, then delegates JSON parsing to {@link
 * FactJsonParser}. The extraction prompt, model call, and parsing all live here.
 *
 * <p><b>Graceful degradation:</b> every failure — no model, a model/stream error, a non-JSON reply
 * — is caught and turned into an empty result (logged at warn), never a thrown exception. So a bad
 * extraction can never break the turn (it just records nothing this time).
 */
public final class LlmMemoryExtractor implements MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);
    private static final int MAX_CHARS_PER_BLOCK = 4000;

    private static final String EXTRACTION_PROMPT = """
            You extract durable long-term memory from a conversation turn. Read the turn below and
            output ONLY a JSON array of fact objects (no prose, no code fences). Each object:
              { "subject": "<stable snake/kebab key, e.g. language or build-tool>",
                "category": "user-preference" | "project-fact" | "reference",
                "statement": "<one short canonical sentence>",
                "confidence": <number 0..1>,
                "correction": <true if the user is correcting/overriding a previously stated fact> }
            RULES:
            - Extract only DURABLE facts: stable user preferences, project/codebase facts, and
              references the user will want later. IGNORE transient status ("I ran the tests"),
              tool output, greetings, and one-off chit-chat.
            - Use a stable `subject` so the same topic dedups across turns (reuse the same key when
              updating a known fact).
            - Set `correction: true` and high confidence when the user corrects an earlier fact.
            - If there is nothing durable, output an empty array: []
            Output JSON only.
            """;

    private final Supplier<Model> modelSupplier;

    public LlmMemoryExtractor(Supplier<Model> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier");
    }

    @Override
    public List<ExtractedFact> extract(List<Msg> turn) {
        if (turn == null || turn.isEmpty()) {
            return List.of();
        }
        try {
            Model model = modelSupplier.get();
            if (model == null) {
                return List.of();
            }
            PigAgent extractor = PigAgent.builder()
                    .name("memory-extractor")
                    .sysPrompt(EXTRACTION_PROMPT)
                    .model(model)
                    .build();
            String conversation = MsgContentRenderer.renderConversation(turn, MAX_CHARS_PER_BLOCK);
            if (conversation.isBlank()) {
                return List.of();
            }
            Msg reply = extractor.call(Msg.builder().name("user").role(MsgRole.USER)
                    .content(TextBlock.builder().text(conversation).build()).build());
            String raw = reply == null ? null : reply.getTextContent();
            return FactJsonParser.parse(raw);
        } catch (Exception e) {
            log.warn("Memory extraction failed (skipped): {}", e.getMessage());
            return List.of();
        }
    }
}
