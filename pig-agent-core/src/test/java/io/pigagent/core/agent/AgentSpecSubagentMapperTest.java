package io.pigagent.core.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Phase 6a — {@link AgentSpecSubagentMapper}: a pig peer {@link AgentSpec} → native
 * {@link SubagentDeclaration}, so a declared agent is both a switchable peer and a delegable subagent
 * (without conflating the two). Verifies the field mapping and — the security-relevant invariant —
 * that the child is declared to inherit the parent's permissions (parent DENY rules propagate).
 */
class AgentSpecSubagentMapperTest {

    @Test
    void mapsIdentityPromptAndToolAllowlist() {
        AgentSpec spec = new AgentSpec("reviewer", "Code Reviewer",
                "You are a meticulous code reviewer.\nFocus on correctness.",
                List.of("readFile", "grep_files"), "plan", "m1", 12);

        SubagentDeclaration d = AgentSpecSubagentMapper.toDeclaration(spec);

        assertThat(d.getName()).as("agent_id used as the subagent name").isEqualTo("reviewer");
        assertThat(d.getDescription())
                .as("description guides the model's delegation decision (never blank)")
                .isNotBlank().contains("Code Reviewer").contains("meticulous code reviewer");
        assertThat(d.getInlineAgentsBody())
                .as("the peer's system prompt becomes the child's persona body")
                .contains("meticulous code reviewer");
        assertThat(d.getTools())
                .as("the peer's tool subset becomes the child's allowlist")
                .containsExactly("readFile", "grep_files");
    }

    @Test
    void childDeclarationRequestsParentPermissionInheritance() {
        SubagentDeclaration d = AgentSpecSubagentMapper.toDeclaration(AgentSpec.create("worker", "Worker"));
        // pig REQUESTS inheritance so that — once the runtime honors it — a delegated task cannot
        // exceed the parent's authority. (2.0.0 leaves this field inert; see SubagentDelegationTest.)
        assertThat(d.isInheritParentPermissions())
                .as("the mapped child requests parent-permission inheritance").isTrue();
    }

    @Test
    void nativeSubagentDeclarationDefaultsToRequestingInheritance() {
        // The declaration field pig relies on defaults true (built-in general-purpose + workspace
        // subagents), so pig is ready for when AgentScope wires inheritance in.
        SubagentDeclaration d = SubagentDeclaration.builder()
                .name("x").description("y").build();
        assertThat(d.isInheritParentPermissions()).isTrue();
    }

    @Test
    void emptyToolsMeansInheritAllParentTools() {
        SubagentDeclaration d = AgentSpecSubagentMapper.toDeclaration(AgentSpec.create("worker", "Worker"));
        assertThat(d.getTools())
                .as("an all-tools peer imposes no allowlist on the child (inherits all)")
                .isNullOrEmpty();
    }

    @Test
    void toDeclarationsExcludesActiveIdAndAutonomousSpecs() {
        AgentSpec self = AgentSpec.create("default", "Default");
        AgentSpec peer = AgentSpec.create("reviewer", "Reviewer");
        AgentSpec nightwatch = AgentSpec.create("nightwatch", "Nightwatch").withSchedule("0 2 * * *");

        List<SubagentDeclaration> out =
                AgentSpecSubagentMapper.toDeclarations(List.of(self, peer, nightwatch), "default");

        assertThat(out).extracting(SubagentDeclaration::getName)
                .as("the active agent and autonomous (scheduled) specs are not spawnable delegation targets")
                .containsExactly("reviewer");
    }

    @Test
    void nullCollectionYieldsEmptyList() {
        assertThat(AgentSpecSubagentMapper.toDeclarations(null, "default")).isEmpty();
    }
}
