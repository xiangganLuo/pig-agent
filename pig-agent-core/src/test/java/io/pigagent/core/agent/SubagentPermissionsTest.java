package io.pigagent.core.agent;

import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubagentPermissions#deriveChildContext} — the pig-side enforcement of subagent
 * permission inheritance (av2 Phase-6b). A spawned child has no confirmer, so the derived context must
 * be at least as restrictive as the parent: parent DENY rules bind, inherited ASK fail-closes to DENY,
 * ALLOW/working-dirs are preserved, and the base mode is conservative (EXPLORE/BYPASS preserved, all
 * else → DONT_ASK).
 */
class SubagentPermissionsTest {

    private static PermissionRule rule(String tool, PermissionBehavior b) {
        return new PermissionRule(tool, null, b, "parent");
    }

    @Test
    void defaultParentDenyRule_isInheritedAndModeFailsClosed() {
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addDenyRule("secretTool", rule("secretTool", PermissionBehavior.DENY))
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getMode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(child.getDenyRules()).containsKey("secretTool");
    }

    @Test
    void inheritedAskRule_isDowngradedToDeny_noConfirmerInChild() {
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAskRule("writeFile", rule("writeFile", PermissionBehavior.ASK))
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getDenyRules()).containsKey("writeFile");
        assertThat(child.getAskRules()).doesNotContainKey("writeFile");
    }

    @Test
    void allowRules_arePreserved() {
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("readFile", rule("readFile", PermissionBehavior.ALLOW))
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getAllowRules()).containsKey("readFile");
    }

    @Test
    void explorePlanParent_keepsChildReadOnly() {
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.EXPLORE)
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getMode())
                .as("a plan-mode (EXPLORE) parent's child stays read-only")
                .isEqualTo(PermissionMode.EXPLORE);
    }

    @Test
    void bypassParent_preservesBypass_butDenyRulesStillBind() {
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.BYPASS)
                .addDenyRule("secretTool", rule("secretTool", PermissionBehavior.DENY))
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getMode()).isEqualTo(PermissionMode.BYPASS);
        assertThat(child.getDenyRules()).containsKey("secretTool");
    }

    @Test
    void channelAutonomousParent_dontAsk_staysFailClosed() {
        // Channel/autonomous parents are built non-interactive (DONT_ASK base): the child stays DONT_ASK.
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                .addDenyRule("executeCommand", rule("executeCommand", PermissionBehavior.DENY))
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getMode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(child.getDenyRules()).containsKey("executeCommand");
    }

    @Test
    void workingDirectories_areCopied() {
        PermissionContextState parent = PermissionContextState.builder()
                .mode(PermissionMode.ACCEPT_EDITS)
                .addWorkingDirectory("/proj", new AdditionalWorkingDirectory("/proj", "userSettings"))
                .build();

        PermissionContextState child = SubagentPermissions.deriveChildContext(parent);

        assertThat(child.getWorkingDirectories()).containsKey("/proj");
        // ACCEPT_EDITS is not read-only/explicit-trust → child base fail-closes.
        assertThat(child.getMode()).isEqualTo(PermissionMode.DONT_ASK);
    }

    @Test
    void nullParent_yieldsMinimalFailClosedContext() {
        PermissionContextState child = SubagentPermissions.deriveChildContext(null);

        assertThat(child.getMode()).isEqualTo(PermissionMode.DONT_ASK);
        assertThat(child.getDenyRules()).isEmpty();
        assertThat(child.getAllowRules()).isEmpty();
        assertThat(child.getAskRules()).isEmpty();
    }
}
