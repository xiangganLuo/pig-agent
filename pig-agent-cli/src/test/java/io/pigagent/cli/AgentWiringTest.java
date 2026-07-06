package io.pigagent.cli;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.PermissionMode;
import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.permission.PermissionDeniedTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWiringTest {

    @Test
    void toolkitFor_emptyNames_returnsSharedToolkit() {
        Toolkit full = new Toolkit();
        assertThat(AgentWiring.toolkitFor(full, List.of())).isSameAs(full);
        assertThat(AgentWiring.toolkitFor(full, null)).isSameAs(full);
    }

    @Test
    void toolkitFor_whitelist_keepsOnlyNamedPlusSentinel_originalUntouched() {
        // Arrange
        Toolkit full = new Toolkit();
        full.registration().tool(new FileSystemTools()).apply();
        full.registration().tool(new PermissionDeniedTool()).apply();
        assertThat(full.getToolNames()).contains("readFile", "writeFile", "listDirectory");

        // Act
        Toolkit sub = AgentWiring.toolkitFor(full, List.of("readFile"));

        // Assert
        assertThat(sub.getToolNames()).contains("readFile", PermissionDeniedTool.TOOL_NAME);
        assertThat(sub.getToolNames()).doesNotContain("writeFile", "listDirectory");
        assertThat(full.getToolNames()).contains("writeFile"); // copy, original intact
    }

    @Test
    void toolkitFor_unknownName_isIgnored() {
        Toolkit full = new Toolkit();
        full.registration().tool(new FileSystemTools()).apply();

        Toolkit sub = AgentWiring.toolkitFor(full, List.of("doesNotExist"));

        assertThat(sub.getToolNames()).doesNotContain("readFile");
    }

    @Test
    void permissionModeOf_parsesKnown_elseNull() {
        assertThat(AgentWiring.permissionModeOf("auto")).isEqualTo(PermissionMode.AUTO);
        assertThat(AgentWiring.permissionModeOf("PLAN")).isEqualTo(PermissionMode.PLAN);
        assertThat(AgentWiring.permissionModeOf(null)).isNull();
        assertThat(AgentWiring.permissionModeOf("   ")).isNull();
        assertThat(AgentWiring.permissionModeOf("bogus")).isNull();
    }
}
