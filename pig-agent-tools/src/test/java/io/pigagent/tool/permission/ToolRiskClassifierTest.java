package io.pigagent.tool.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 工具风险分级：默认表 + overrides 覆盖 + 未知→EXEC。 */
class ToolRiskClassifierTest {

    @Test
    void knownToolsClassifiedByDefaultTable() {
        assertThat(ToolRiskClassifier.classify("readFile", Map.of())).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(ToolRiskClassifier.classify("writeFile", Map.of())).isEqualTo(ToolRisk.WRITE);
        assertThat(ToolRiskClassifier.classify("executeCommand", Map.of())).isEqualTo(ToolRisk.EXEC);
        assertThat(ToolRiskClassifier.classify("fetchUrl", Map.of())).isEqualTo(ToolRisk.NETWORK);
        // H-1: webSearch reaches the public Brave API without the SSRF guard → NETWORK (was READ_ONLY).
        assertThat(ToolRiskClassifier.classify("webSearch", Map.of())).isEqualTo(ToolRisk.NETWORK);
        // M-3: checklist create/complete mutate state (WRITE); showChecklist is READ_ONLY.
        assertThat(ToolRiskClassifier.classify("createChecklist", Map.of())).isEqualTo(ToolRisk.WRITE);
        assertThat(ToolRiskClassifier.classify("completeItem", Map.of())).isEqualTo(ToolRisk.WRITE);
        assertThat(ToolRiskClassifier.classify("showChecklist", Map.of())).isEqualTo(ToolRisk.READ_ONLY);
        // proactive-outreach: notifyUser reaches the user over an outbound channel → NETWORK.
        assertThat(ToolRiskClassifier.classify("notifyUser", Map.of())).isEqualTo(ToolRisk.NETWORK);
        assertThat(ToolRiskClassifier.classify("addMcpServer", Map.of())).isEqualTo(ToolRisk.MCP_ADMIN);
    }

    @Test
    void collectionPureComputeToolsAreReadOnly() {
        // 内置插件集合（pig-agent-plugin-collection）的纯计算工具应干净放行
        for (String name : new String[]{
                "currentDateTime", "convertTimezone", "epochToIso", "isoToEpoch",
                "generateUuid", "base64Encode", "base64Decode", "md5Hash", "sha256Hash",
                "jsonPrettyPrint", "jsonValidate", "randomNumber", "randomString"}) {
            assertThat(ToolRiskClassifier.classify(name, Map.of()))
                    .as("tool %s should be READ_ONLY", name)
                    .isEqualTo(ToolRisk.READ_ONLY);
        }
    }

    @Test
    void toolSearchIsReadOnly() {
        // deferred-tools: tool_search 只搜索/揭示元数据，必须只读（不触发权限确认）
        assertThat(ToolRiskClassifier.classify("tool_search", Map.of())).isEqualTo(ToolRisk.READ_ONLY);
    }

    @Test
    void unknownToolDefaultsToExecFailSafe() {
        assertThat(ToolRiskClassifier.classify("someRandomTool", Map.of())).isEqualTo(ToolRisk.EXEC);
        assertThat(ToolRiskClassifier.classify(null, Map.of())).isEqualTo(ToolRisk.EXEC);
        assertThat(ToolRiskClassifier.classify("  ", Map.of())).isEqualTo(ToolRisk.EXEC);
    }

    @Test
    void overridesTakePrecedence() {
        assertThat(ToolRiskClassifier.classify("fetchUrl", Map.of("fetchUrl", "EXEC")))
                .isEqualTo(ToolRisk.EXEC);
        // 大小写不敏感
        assertThat(ToolRiskClassifier.classify("writeFile", Map.of("writeFile", "read_only")))
                .isEqualTo(ToolRisk.READ_ONLY);
    }

    @Test
    void illegalOverrideValueFallsBackToDefault() {
        assertThat(ToolRiskClassifier.classify("writeFile", Map.of("writeFile", "garbage")))
                .isEqualTo(ToolRisk.WRITE);
    }
}
