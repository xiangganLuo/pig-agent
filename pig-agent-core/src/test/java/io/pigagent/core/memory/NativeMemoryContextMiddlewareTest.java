package io.pigagent.core.memory;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.pigagent.core.memory.injection.MemoryInjectionSettings;
import io.pigagent.core.memory.injection.MemoryRetriever;
import io.pigagent.core.memory.injection.PinnedSource;
import io.pigagent.core.memory.search.MemoryDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline coverage for {@link NativeMemoryContextMiddleware}. The first block preserves the original
 * {@code pa-memory-native} whole-{@code MEMORY.md} injection behavior (byte-identical when retrieval
 * injection is off). The second block is the {@code memory-retrieval-injection} <b>Spike</b>: it nails
 * the load-bearing prefix-cache structural invariants — the pinned core (system prompt) is
 * query-independent and byte-stable, and query-aware facts appear only in a trailing ephemeral message
 * (never in the system prompt, never written back to the input list).
 */
class NativeMemoryContextMiddlewareTest {

    // ---- default (whole-file) mode: unchanged pa-memory-native behavior ----

    @Test
    void injectsMemoryMdContentIntoSystemPrompt(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "- User's name is 罗湘赣\n- Prefers Chinese replies", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory);

        String result = mw.onSystemPrompt(null, null, "BASE PROMPT").block();

        assertThat(result).startsWith("BASE PROMPT");
        assertThat(result).contains(NativeMemoryContextMiddleware.MEMORY_HEADER);
        assertThat(result).contains("罗湘赣").contains("Prefers Chinese replies");
    }

    @Test
    void injectionIsStableWhileFileUnchanged(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "- fact one", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory);

        String first = mw.onSystemPrompt(null, null, "SYS").block();
        String second = mw.onSystemPrompt(null, null, "SYS").block();

        assertThat(second).isEqualTo(first); // byte-stable within a session (prefix-cache friendly)
    }

    @Test
    void missingMemoryFile_leavesPromptUnchanged(@TempDir Path ws) {
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(ws.resolve("MEMORY.md"));

        String result = mw.onSystemPrompt(null, null, "ONLY THE BASE").block();

        assertThat(result).isEqualTo("ONLY THE BASE");
    }

    @Test
    void blankMemoryFile_leavesPromptUnchanged(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "   \n\n  ", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory);

        String result = mw.onSystemPrompt(null, null, "BASE").block();

        assertThat(result).isEqualTo("BASE");
    }

    @Test
    void injectionDisabledSettings_isByteIdenticalToWholeFileMode(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "- fact one\n- fact two", StandardCharsets.UTF_8);
        NativeMemoryContextMiddleware whole = new NativeMemoryContextMiddleware(memory);
        NativeMemoryContextMiddleware disabled = new NativeMemoryContextMiddleware(
                memory, MemoryInjectionSettings.defaults(), (q, k) -> List.of()); // defaults().enabled()==false

        assertThat(disabled.onSystemPrompt(null, null, "SYS").block())
                .isEqualTo(whole.onSystemPrompt(null, null, "SYS").block());
    }

    // ---- memory-retrieval-injection Spike: prefix-cache structural invariants ----

    private static final String MEMORY_MD = String.join("\n",
            "## Pinned",
            "- User's name is 罗湘赣",
            "",
            "## Facts",
            "- The user prefers dark mode themes",
            "- The project deploys on Fridays");

    private NativeMemoryContextMiddleware injecting(Path memory, MemoryRetriever retriever) throws IOException {
        Files.writeString(memory, MEMORY_MD, StandardCharsets.UTF_8);
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Pinned", 800);
        return new NativeMemoryContextMiddleware(memory, s, retriever);
    }

    private static ReasoningInput reasoning(Msg... msgs) {
        return new ReasoningInput(new ArrayList<>(List.of(msgs)), List.of(), null);
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build();
    }

    private static ReasoningInput passThrough(NativeMemoryContextMiddleware mw, ReasoningInput in) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        Function<ReasoningInput, Flux<AgentEvent>> next = i -> {
            seen.set(i);
            return Flux.empty();
        };
        mw.onReasoning(null, null, in, next).blockLast();
        return seen.get();
    }

    @Test
    void injectionEnabled_systemPromptCarriesOnlyPinnedCore(@TempDir Path ws) throws IOException {
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"),
                (q, k) -> List.of(new MemoryDocument("f", "MEMORY.md", "The user prefers dark mode themes")));

        String prompt = mw.onSystemPrompt(null, null, "SYS").block();

        assertThat(prompt).contains(NativeMemoryContextMiddleware.PINNED_HEADER);
        assertThat(prompt).contains("罗湘赣");                       // pinned core present
        assertThat(prompt).doesNotContain("dark mode");             // non-pinned fact NOT in system prompt
        assertThat(prompt).doesNotContain("deploys on Fridays");
    }

    @Test
    void spike_systemPromptIsByteIdenticalRegardlessOfQuery(@TempDir Path ws) throws IOException {
        // The retriever varies wildly per query, but onSystemPrompt has no query input → prompt is fixed.
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"),
                (q, k) -> List.of(new MemoryDocument("f", "MEMORY.md", "fact about " + q)));

        String a = mw.onSystemPrompt(null, null, "SYS").block();
        String b = mw.onSystemPrompt(null, null, "SYS").block();

        assertThat(b).isEqualTo(a); // query-independent, byte-stable cached prefix
    }

    @Test
    void spike_queryAwareFactsAppearOnlyInTrailingEphemeralMessage(@TempDir Path ws) throws IOException {
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"),
                (q, k) -> List.of(new MemoryDocument("f", "memory/2026.md", "Deploy window is Friday 18:00")));

        // system prompt must NOT contain the retrieved fact
        String prompt = mw.onSystemPrompt(null, null, "SYS").block();
        assertThat(prompt).doesNotContain("Deploy window is Friday 18:00");

        // the retrieved fact must appear in a trailing user-side ephemeral message
        ReasoningInput out = passThrough(mw, reasoning(user("when do we deploy?")));
        List<Msg> messages = out.messages();
        Msg trailing = messages.get(messages.size() - 1);
        assertThat(trailing.getRole()).isEqualTo(MsgRole.USER);
        assertThat(trailing.getTextContent()).contains("Deploy window is Friday 18:00");
    }

    @Test
    void spike_injectionDoesNotMutateInputMessages_andIsRebuilt(@TempDir Path ws) throws IOException {
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"),
                (q, k) -> List.of(new MemoryDocument("f", "MEMORY.md", "Ephemeral note not persisted")));

        ReasoningInput in = reasoning(user("hi"));
        int originalSize = in.messages().size();

        ReasoningInput out = passThrough(mw, in);

        assertThat(in.messages()).hasSize(originalSize);            // incoming list untouched
        assertThat(out).isNotSameAs(in);                            // a NEW ReasoningInput
        assertThat(out.messages()).hasSize(originalSize + 1);       // trailing message added to the copy
    }

    @Test
    void spike_differentQueriesProduceDifferentTrailingContent(@TempDir Path ws) throws IOException {
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"),
                (q, k) -> List.of(new MemoryDocument("f", "MEMORY.md", "relevant to " + q)));

        ReasoningInput outA = passThrough(mw, reasoning(user("alpha")));
        ReasoningInput outB = passThrough(mw, reasoning(user("beta")));

        String lastA = outA.messages().get(outA.messages().size() - 1).getTextContent();
        String lastB = outB.messages().get(outB.messages().size() - 1).getTextContent();
        assertThat(lastA).contains("alpha");
        assertThat(lastB).contains("beta");
        assertThat(lastA).isNotEqualTo(lastB);
    }

    @Test
    void injectionEnabled_blankQuery_noInjection(@TempDir Path ws) throws IOException {
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"),
                (q, k) -> List.of(new MemoryDocument("f", "MEMORY.md", "should not appear")));

        ReasoningInput in = reasoning(user("   "));
        ReasoningInput out = passThrough(mw, in);

        assertThat(out).isSameAs(in); // identity — nothing injected
    }

    @Test
    void injectionEnabled_emptyResults_noInjection(@TempDir Path ws) throws IOException {
        NativeMemoryContextMiddleware mw = injecting(ws.resolve("MEMORY.md"), (q, k) -> List.of());

        ReasoningInput in = reasoning(user("anything relevant?"));
        ReasoningInput out = passThrough(mw, in);

        assertThat(out).isSameAs(in);
    }

    @Test
    void injectionEnabled_pinnedHeadingAbsent_systemPromptUnchanged(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, "## Facts\n- only non-pinned facts here", StandardCharsets.UTF_8);
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Pinned", 800);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory, s, (q, k) -> List.of());

        String prompt = mw.onSystemPrompt(null, null, "BASE").block();

        assertThat(prompt).isEqualTo("BASE"); // no pinned section → system prompt untouched
    }

    @Test
    void nullRetriever_noQueryAwareInjection(@TempDir Path ws) throws IOException {
        Path memory = ws.resolve("MEMORY.md");
        Files.writeString(memory, MEMORY_MD, StandardCharsets.UTF_8);
        MemoryInjectionSettings s = new MemoryInjectionSettings(true, 6, PinnedSource.HEADING, "Pinned", 800);
        NativeMemoryContextMiddleware mw = new NativeMemoryContextMiddleware(memory, s, null);

        ReasoningInput in = reasoning(user("query"));
        assertThat(passThrough(mw, in)).isSameAs(in);
    }
}
