package io.pigagent.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that the fixed {@link AgentBootstrap#TOOL_GUIDANCE} block appended to the system prompt
 * stays aligned with the final capability set and keeps its always-on constraints. Anchor
 * assertions only — the exact wording may evolve without breaking the test.
 */
class AgentBootstrapToolGuidanceTest {

    @Test
    void toolGuidanceIsNonBlankAndByteStable() {
        // Arrange + Act
        String guidance = AgentBootstrap.TOOL_GUIDANCE;

        // Assert — a constant, so identical across reads (byte-stable per run)
        assertThat(guidance).isNotBlank();
        assertThat(guidance).isEqualTo(AgentBootstrap.TOOL_GUIDANCE);
    }

    @Test
    void toolGuidanceEnforcesFileToolsOverShell() {
        String guidance = AgentBootstrap.TOOL_GUIDANCE;

        assertThat(guidance).contains("writeFile", "readFile", "listDirectory");
        // Steers file CRUD away from the shell (executeCommand).
        assertThat(guidance).contains("executeCommand");
        assertThat(guidance).containsIgnoringCase("NEVER use executeCommand");
    }

    @Test
    void toolGuidanceCoversDenialAndUnavailability() {
        String guidance = AgentBootstrap.TOOL_GUIDANCE;

        assertThat(guidance).containsIgnoringCase("denied");
        assertThat(guidance).containsIgnoringCase("unavailable");
        assertThat(guidance).contains("webSearch");
    }

    @Test
    void toolGuidanceForbidsEchoingSecrets() {
        String guidance = AgentBootstrap.TOOL_GUIDANCE;

        assertThat(guidance).containsIgnoringCase("secret");
        assertThat(guidance).containsIgnoringCase("credential");
    }
}
