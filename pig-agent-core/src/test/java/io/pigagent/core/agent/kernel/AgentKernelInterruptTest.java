package io.pigagent.core.agent.kernel;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.AgentSpecRepository;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.interrupt.InterruptController;
import io.pigagent.core.interrupt.InterruptibleModel;
import io.pigagent.core.interrupt.TurnInterruptedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Group 3: {@code AgentKernel.interruptCurrent()} — registers a cancellable turn on {@code chat},
 * fires the interrupt for the in-flight turn, and is a no-op when idle.
 */
class AgentKernelInterruptTest {

    /** A model whose stream never completes on its own, so the turn stays in flight until interrupt. */
    private static Model neverModel() {
        return new Model() {
            @Override
            public String getModelName() {
                return "never";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return Flux.never();
            }
        };
    }

    private static Msg userMsg() {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build()).build();
    }

    private AgentInstanceFactory factory() {
        return new AgentInstanceFactory(
                spec -> mock(Model.class), spec -> new io.agentscope.core.tool.Toolkit(),
                spec -> List.of(), null);
    }

    @Test
    void interruptCurrent_noActiveTurn_returnsFalse(@TempDir Path dir) {
        InterruptController controller = new InterruptController();
        AgentInstanceFactory factory = factory();
        AgentInstance def = factory.create(AgentSpec.create("default", "Default"));
        AgentRegistry registry = new AgentRegistry(new AgentHolder(def.agent()));
        registry.register(def);
        AgentKernel kernel = new AgentKernel(registry, new AgentSpecRepository(dir), factory, null, controller);

        assertThat(kernel.interruptCurrent()).isFalse();
    }

    @Test
    void interruptCurrent_duringChat_cancelsTurn_thenClears(@TempDir Path dir) throws Exception {
        // Arrange — a real agent whose model is interruptible and shares the kernel's controller.
        InterruptController controller = new InterruptController();
        PigAgent agent = PigAgent.builder().name("t").sysPrompt("s")
                .model(new InterruptibleModel(neverModel(), controller)).build();
        AgentInstance def = new AgentInstance("default", AgentSpec.create("default", "Default"), agent);
        AgentRegistry registry = new AgentRegistry(new AgentHolder(agent));
        registry.register(def);
        AgentKernel kernel = new AgentKernel(registry, new AgentSpecRepository(dir), factory(), null, controller);

        CountDownLatch terminated = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Disposable sub = kernel.chat("default", userMsg())
                .subscribe(e -> { }, t -> { error.set(t); terminated.countDown(); }, terminated::countDown);

        // Act — a turn is now in flight; interrupt it.
        boolean fired = kernel.interruptCurrent();
        boolean done = terminated.await(5, TimeUnit.SECONDS);

        // Assert — interrupt fired, stream terminated by interruption, and the turn is cleared.
        assertThat(fired).isTrue();
        assertThat(done).isTrue();
        assertThat(hasInterruptCause(error.get())).isTrue();
        assertThat(kernel.interruptCurrent()).isFalse(); // turn cleared after termination
        sub.dispose();
    }

    private static boolean hasInterruptCause(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof TurnInterruptedException) {
                return true;
            }
        }
        return false;
    }

    @Test
    void chat_normalTermination_clearsTurn(@TempDir Path dir) {
        // A model that completes immediately leaves no lingering turn afterwards.
        InterruptController controller = new InterruptController();
        Model quick = new Model() {
            @Override
            public String getModelName() {
                return "quick";
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("done").build()))
                        .finishReason("stop").build());
            }
        };
        PigAgent agent = PigAgent.builder().name("t").sysPrompt("s")
                .model(new InterruptibleModel(quick, controller)).build();
        AgentInstance def = new AgentInstance("default", AgentSpec.create("default", "Default"), agent);
        AgentRegistry registry = new AgentRegistry(new AgentHolder(agent));
        registry.register(def);
        AgentKernel kernel = new AgentKernel(registry, new AgentSpecRepository(dir), factory(), null, controller);

        List<Event> events = kernel.chat("default", userMsg()).collectList().block();

        assertThat(events).isNotNull();
        // The turn is cleared in the stream's doFinally, which runs after the terminal signal has
        // propagated to block() — so under parallel builds the clear can lag the block() return by a
        // hair. Deterministically await the clear (side-effect-free read of the shared controller)
        // instead of racing on a bare read; this still verifies "no lingering turn after completion".
        awaitTurnCleared(controller);
        assertThat(kernel.interruptCurrent()).isFalse(); // no lingering turn after normal completion
    }

    /** Poll (side-effect-free) until the shared controller reports no in-flight turn, or time out. */
    private static void awaitTurnCleared(InterruptController controller) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (controller.currentTurn().isPresent() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(controller.currentTurn())
                .as("turn must clear via doFinally after normal completion")
                .isEmpty();
    }
}
