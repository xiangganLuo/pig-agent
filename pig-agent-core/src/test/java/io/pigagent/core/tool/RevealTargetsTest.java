package io.pigagent.core.tool;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** RevealTargets: register (null-safe), broadcast group activation across registered toolkits, size. */
class RevealTargetsTest {

    static final class Fixture {
        @Tool(name = "hidden", description = "a hidden tool")
        public String hidden() {
            return "x";
        }
    }

    private static Set<String> names(Toolkit tk) {
        return tk.getToolSchemas().stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    private static Toolkit toolkitWithHiddenInInactiveGroup() {
        Toolkit tk = new Toolkit();
        tk.createToolGroup("grp", "grp", false); // inactive
        tk.registration().tool(new Fixture()).group("grp").apply();
        return tk;
    }

    @Test
    void activateGroupBroadcastsToAllRegisteredToolkits() {
        Toolkit a = toolkitWithHiddenInInactiveGroup();
        Toolkit b = toolkitWithHiddenInInactiveGroup();
        assertThat(names(a)).doesNotContain("hidden");
        assertThat(names(b)).doesNotContain("hidden");

        RevealTargets targets = new RevealTargets();
        targets.register(a);
        targets.register(b);
        targets.activateGroup("grp");

        assertThat(names(a)).contains("hidden");
        assertThat(names(b)).contains("hidden");
    }

    @Test
    void registerIgnoresNullAndDeduplicates() {
        RevealTargets targets = new RevealTargets();
        targets.register(null);
        assertThat(targets.size()).isZero();
        Toolkit tk = new Toolkit();
        targets.register(tk);
        targets.register(tk);
        assertThat(targets.size()).isEqualTo(1);
    }

    @Test
    void activateGroupToleratesNullBlankAndNoTargets() {
        RevealTargets targets = new RevealTargets();
        assertThatCode(() -> {
            targets.activateGroup(null);
            targets.activateGroup("  ");
            targets.activateGroup("nope"); // no registered toolkits
        }).doesNotThrowAnyException();
    }
}
