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
        // autonomous-skills: proposeSkill/skillManage write staged drafts → WRITE (permission-governed).
        assertThat(ToolRiskClassifier.classify("proposeSkill", Map.of())).isEqualTo(ToolRisk.WRITE);
        assertThat(ToolRiskClassifier.classify("skillManage", Map.of())).isEqualTo(ToolRisk.WRITE);
        // user-profile: updateProfile writes a field to USER.md → WRITE.
        assertThat(ToolRiskClassifier.classify("updateProfile", Map.of())).isEqualTo(ToolRisk.WRITE);
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
    void richFileToolsClassified() {
        // builtin-file-tools: editFile mutates a file → WRITE; searchFiles/findFiles are read-only
        // traversals → READ_ONLY (so plan/EXPLORE mode permits them).
        assertThat(ToolRiskClassifier.classify("editFile", Map.of())).isEqualTo(ToolRisk.WRITE);
        assertThat(ToolRiskClassifier.classify("searchFiles", Map.of())).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(ToolRiskClassifier.classify("findFiles", Map.of())).isEqualTo(ToolRisk.READ_ONLY);
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
    void namespacedMcpToolsAreFailSafeExec() {
        // T4 (mcp-namespace-and-capability-groups): a namespaced MCP-server tool (mcp__server__tool)
        // is never in the built-in table, so it MUST fall to the fail-safe EXEC — a mutating/dangerous
        // MCP tool can never be accidentally allowed.
        assertThat(ToolRiskClassifier.classify("mcp__fs__deleteAll", Map.of())).isEqualTo(ToolRisk.EXEC);
        assertThat(ToolRiskClassifier.classify("mcp__weather__forecast", Map.of())).isEqualTo(ToolRisk.EXEC);
    }

    @Test
    void namespacedMcpToolDoesNotSpoofBuiltinReadOnly() {
        // Anti-spoof (security): a server naming a tool "readFile" registers as mcp__evil__readFile;
        // the classifier MUST NOT fall back to the base name and inherit the built-in readFile's
        // READ_ONLY — it stays fail-safe EXEC.
        assertThat(ToolRiskClassifier.classify("mcp__evil__readFile", Map.of())).isEqualTo(ToolRisk.EXEC);
        assertThat(ToolRiskClassifier.classify("mcp__evil__listDirectory", Map.of())).isEqualTo(ToolRisk.EXEC);
    }

    @Test
    void namespacedMcpToolOverrideByFullNameApplies() {
        // Overrides/allowlist for a namespaced MCP tool are keyed by the FULL namespaced name.
        assertThat(ToolRiskClassifier.classify("mcp__weather__forecast",
                Map.of("mcp__weather__forecast", "READ_ONLY"))).isEqualTo(ToolRisk.READ_ONLY);
        // A base-name override MUST NOT leak onto the namespaced tool.
        assertThat(ToolRiskClassifier.classify("mcp__weather__forecast",
                Map.of("forecast", "READ_ONLY"))).isEqualTo(ToolRisk.EXEC);
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
