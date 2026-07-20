package io.pigagent.tool.deferred;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.tool.RevealTargets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reveal state across {@code Toolkit.copy()} ({@code deferred-tools} fix): a {@code tool_search} reveal
 * must reach the copy the agent actually runs on (peer / subagent), not only the base toolkit. Because
 * {@code Toolkit.copy()} gives INDEPENDENT group active-state, the reveal is broadcast to every
 * registered toolkit via {@link RevealTargets}.
 */
class DeferredToolRevealBroadcastTest {

    private static Set<String> names(Toolkit tk) {
        return tk.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    private static DeferredToolRegistry deferWeatherOn(Toolkit base) {
        return DeferredToolGate.applyTo(base, new DeferralPlan(Set.of("getWeather")),
                List.of(ToolInfo.of("getWeather", "weather", null)));
    }

    @Test
    void revealReachesRegisteredCopyNotOnlyBase() {
        // Arrange: defer getWeather on base, then take a copy (a peer/subagent Toolkit.copy).
        Toolkit base = new Toolkit();
        base.registration().tool(new DeferredSampleTools()).apply();
        DeferredToolRegistry reg = deferWeatherOn(base);
        Toolkit copy = base.copy();
        assertThat(names(copy)).doesNotContain("getWeather"); // copy inherits the deferred (hidden) state

        RevealTargets targets = new RevealTargets();
        targets.register(base);
        targets.register(copy);
        DeferredToolReveal reveal = DeferredToolGate.reveal(targets, reg);

        // Act
        boolean revealed = reveal.reveal("getWeather");

        // Assert: the reveal reached BOTH the base and the copy (the fix).
        assertThat(revealed).isTrue();
        assertThat(names(copy)).contains("getWeather");
        assertThat(names(base)).contains("getWeather");
        assertThat(reg.find("getWeather")).isEmpty(); // marked revealed
    }

    @Test
    void oldSingleToolkitSeamDoesNotReachCopy_documentsTheBugBeingFixed() {
        // The pre-fix reveal bound to a single (base) toolkit does NOT propagate to a copy — the very
        // limitation this change removes. Kept as a guard so the two seams stay distinct.
        Toolkit base = new Toolkit();
        base.registration().tool(new DeferredSampleTools()).apply();
        DeferredToolRegistry reg = deferWeatherOn(base);
        Toolkit copy = base.copy();

        DeferredToolReveal baseOnly = DeferredToolGate.reveal(base, reg);
        baseOnly.reveal("getWeather");

        assertThat(names(base)).contains("getWeather");
        assertThat(names(copy)).doesNotContain("getWeather"); // copy stays hidden (the bug)
    }

    @Test
    void unregisteredCopyIsUnaffectedButDoesNotError() {
        // A copy that never registered simply does not receive the reveal (no crash); this is why peer /
        // subagent copies must register (done by the cli wiring / PigAgent.Builder in production).
        Toolkit base = new Toolkit();
        base.registration().tool(new DeferredSampleTools()).apply();
        DeferredToolRegistry reg = deferWeatherOn(base);
        Toolkit unregistered = base.copy();

        RevealTargets targets = new RevealTargets();
        targets.register(base);
        DeferredToolGate.reveal(targets, reg).reveal("getWeather");

        assertThat(names(base)).contains("getWeather");
        assertThat(names(unregistered)).doesNotContain("getWeather");
    }
}
